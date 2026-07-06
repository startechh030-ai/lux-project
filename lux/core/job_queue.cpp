#include "core/job_queue.h"
#include <thread>
#include <android/log.h>

#define LOG_TAG "LuxJobs"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace lux {

// ══════════════════════════════════════════════════════════════════════════
//  JobQueue (SPSC lock-free queue)
// ══════════════════════════════════════════════════════════════════════════

JobQueue::JobQueue(size_t capacity)
    : capacity_(capacity) {
    // Must be power of 2 for efficient modulo
    size_t actualCap = 1;
    while (actualCap < capacity) actualCap <<= 1;
    const_cast<size_t&>(capacity_) = actualCap;

    slots_ = new Slot[actualCap];
    for (size_t i = 0; i < actualCap; ++i) {
        slots_[i].sequence.store(i, std::memory_order_relaxed);
    }
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}

JobQueue::~JobQueue() {
    delete[] slots_;
}

bool JobQueue::push(Job&& job) {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t tail = tail_.load(std::memory_order_acquire);
    size_t mask = capacity_ - 1;

    if ((tail - head) >= capacity_) return false; // Full

    Slot& slot = slots_[tail & mask];
    if (slot.sequence.load(std::memory_order_acquire) != tail) return false;

    slot.job = std::move(job);
    slot.sequence.store(tail + 1, std::memory_order_release);
    tail_.store(tail + 1, std::memory_order_release);
    return true;
}

bool JobQueue::pop(Job& outJob) {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t tail = tail_.load(std::memory_order_acquire);
    size_t mask = capacity_ - 1;

    if (head >= tail) return false; // Empty

    Slot& slot = slots_[head & mask];
    if (slot.sequence.load(std::memory_order_acquire) != head + 1) return false;

    outJob = std::move(slot.job);
    slot.sequence.store(head + capacity_, std::memory_order_release);
    head_.store(head + 1, std::memory_order_release);
    return true;
}

size_t JobQueue::size() const {
    return tail_.load(std::memory_order_acquire) - head_.load(std::memory_order_acquire);
}

void JobQueue::clear() {
    Job dummy;
    while (pop(dummy)) {}
}

// ══════════════════════════════════════════════════════════════════════════
//  JobSystem
// ══════════════════════════════════════════════════════════════════════════

JobSystem::JobSystem(size_t threadCount) {
    if (threadCount == 0) {
        threadCount = std::max(1u, std::thread::hardware_concurrency());
    }
    threadCount_ = threadCount;
    running_.store(true);

    workers_.reserve(threadCount_);
    for (size_t i = 0; i < threadCount_; ++i) {
        workers_.emplace_back(&JobSystem::workerLoop, this, static_cast<int>(i));
    }

    LOGI("JobSystem started with %zu worker threads", threadCount_);
}

JobSystem::~JobSystem() {
    running_.store(false);
    // Wake up all workers so they can exit
    {
        std::lock_guard<std::mutex> lock(waiterMutex_);
        waiterCv_.notify_all();
    }
    for (auto& worker : workers_) {
        if (worker.joinable()) worker.join();
    }
    LOGI("JobSystem shut down");
}

void JobSystem::schedule(Job&& job) {
    pendingJobs_.fetch_add(1, std::memory_order_release);
    while (!queue_.push(std::move(job))) {
        // Spin if full — could yield
        std::this_thread::yield();
    }
    // Wake one worker
    waiterCv_.notify_one();
}

void JobSystem::waitForAll() {
    std::unique_lock<std::mutex> lock(waiterMutex_);
    waiterCv_.wait(lock, [this]() {
        return pendingJobs_.load(std::memory_order_acquire) == 0;
    });
}

void JobSystem::workerLoop(int workerId) {
    (void)workerId;
    Job job;

    while (running_.load(std::memory_order_relaxed)) {
        if (queue_.pop(job)) {
            job();
            int remaining = pendingJobs_.fetch_sub(1, std::memory_order_acq_rel);
            if (remaining <= 1) {
                // Last job completed — notify waiters
                std::lock_guard<std::mutex> lock(waiterMutex_);
                waiterCv_.notify_all();
            }
        } else {
            // No work available — wait
            std::unique_lock<std::mutex> lock(waiterMutex_);
            waiterCv_.wait_for(lock, std::chrono::milliseconds(10));
        }
    }
}

} // namespace lux
