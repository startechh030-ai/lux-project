#ifndef LUX_CORE_JOB_QUEUE_H
#define LUX_CORE_JOB_QUEUE_H

#include <cstddef>
#include <cstdint>
#include <functional>

namespace lux {

/// A lightweight job/task that can be enqueued and executed by a worker thread.
using Job = std::function<void()>;

/// A lock-free single-producer, single-consumer (SPSC) queue for jobs.
/// Suitable for dispatching work from the main thread to a worker.
class JobQueue {
public:
    explicit JobQueue(size_t capacity = 256);
    ~JobQueue();

    JobQueue(const JobQueue&) = delete;
    JobQueue& operator=(const JobQueue&) = delete;

    /// Enqueue a job. Returns false if the queue is full.
    bool push(Job&& job);

    /// Dequeue a job. Returns false if the queue is empty.
    bool pop(Job& outJob);

    /// Returns the number of jobs currently in the queue.
    size_t size() const;

    /// Returns the maximum capacity of the queue.
    size_t capacity() const { return capacity_; }

    /// Clear all pending jobs.
    void clear();

private:
    struct alignas(64) Slot {
        Job job;
        std::atomic<uint64_t> sequence;
    };

    const size_t capacity_;
    Slot* slots_ = nullptr;

    // Align to cache line to avoid false sharing
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

/// A simple pool of worker threads that pull jobs from a shared queue.
class JobSystem {
public:
    explicit JobSystem(size_t threadCount = 0);
    ~JobSystem();

    JobSystem(const JobSystem&) = delete;
    JobSystem& operator=(const JobSystem&) = delete;

    /// Schedule a job to be executed by any worker thread.
    void schedule(Job&& job);

    /// Wait until all currently scheduled jobs have completed.
    void waitForAll();

    /// Returns the number of worker threads.
    size_t threadCount() const { return threadCount_; }

private:
    void workerLoop(int workerId);

    JobQueue queue_;
    size_t threadCount_;
    std::vector<std::thread> workers_;
    std::atomic<int> pendingJobs_{0};
    std::atomic<bool> running_{true};
    std::mutex waiterMutex_;
    std::condition_variable waiterCv_;
};

} // namespace lux

#endif // LUX_CORE_JOB_QUEUE_H
