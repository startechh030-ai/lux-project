#include "core/memory_allocator.h"
#include <cstdlib>
#include <cstring>
#include <new>
#include <android/log.h>

#define LOG_TAG "LuxMemory"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace lux {

// ══════════════════════════════════════════════════════════════════════════
//  LinearAllocator
// ══════════════════════════════════════════════════════════════════════════

LinearAllocator::LinearAllocator(size_t capacity)
    : capacity_(capacity) {
    buffer_ = static_cast<uint8_t*>(aligned_alloc(kDefaultAlignment, capacity_));
    if (!buffer_) throw std::bad_alloc();
}

LinearAllocator::~LinearAllocator() {
    if (ownsMemory_ && buffer_) {
        std::free(buffer_);
    }
}

LinearAllocator::LinearAllocator(LinearAllocator&& other) noexcept
    : buffer_(other.buffer_)
    , capacity_(other.capacity_)
    , offset_(other.offset_)
    , ownsMemory_(other.ownsMemory_) {
    other.buffer_ = nullptr;
    other.capacity_ = 0;
    other.offset_ = 0;
}

LinearAllocator& LinearAllocator::operator=(LinearAllocator&& other) noexcept {
    if (this != &other) {
        if (ownsMemory_ && buffer_) std::free(buffer_);
        buffer_ = other.buffer_;
        capacity_ = other.capacity_;
        offset_ = other.offset_;
        ownsMemory_ = other.ownsMemory_;
        other.buffer_ = nullptr;
        other.capacity_ = 0;
        other.offset_ = 0;
    }
    return *this;
}

void* LinearAllocator::allocate(size_t size) {
    return allocateAligned(size, kDefaultAlignment);
}

void* LinearAllocator::allocateAligned(size_t size, size_t alignment) {
    // Align the current offset up to the requested alignment
    size_t aligned = (offset_ + alignment - 1) & ~(alignment - 1);
    if (aligned + size > capacity_) {
        LOGW("LinearAllocator OOM: wanted %zu bytes, capacity=%zu, used=%zu",
             size, capacity_, offset_);
        return nullptr;
    }
    offset_ = aligned + size;
    return buffer_ + aligned;
}

void LinearAllocator::deallocate(void* /*ptr*/) {
    // Linear allocator: individual deallocation is a no-op.
}

void LinearAllocator::reset() {
    offset_ = 0;
}

size_t LinearAllocator::used() const {
    return offset_;
}

// ══════════════════════════════════════════════════════════════════════════
//  PoolAllocator
// ══════════════════════════════════════════════════════════════════════════

PoolAllocator::PoolAllocator(size_t objectSize, size_t objectAlignment, size_t objectCount)
    : objectSize_(objectSize)
    , objectAlignment_(objectAlignment)
    , objectCount_(objectCount) {
    // Ensure each slot holds at least a size_t for the free-list index
    size_t slotSize = (objectSize_ > sizeof(size_t)) ? objectSize_ : sizeof(size_t);
    // Align slot size
    slotSize = (slotSize + objectAlignment_ - 1) & ~(objectAlignment_ - 1);

    size_t totalSize = slotSize * objectCount_;
    buffer_ = static_cast<uint8_t*>(aligned_alloc(kDefaultAlignment, totalSize));
    if (!buffer_) throw std::bad_alloc();

    // Build free-list: each slot stores the index of the next free slot
    nextFree_ = reinterpret_cast<size_t*>(buffer_);
    for (size_t i = 0; i < objectCount_ - 1; ++i) {
        size_t* slot = reinterpret_cast<size_t*>(buffer_ + slotSize * i);
        *slot = i + 1;
    }
    // Last slot points to sentinel (objectCount_)
    size_t* lastSlot = reinterpret_cast<size_t*>(buffer_ + slotSize * (objectCount_ - 1));
    *lastSlot = objectCount_;
}

PoolAllocator::~PoolAllocator() {
    std::free(buffer_);
}

void* PoolAllocator::allocate(size_t size) {
    return allocateAligned(size, objectAlignment_);
}

void* PoolAllocator::allocateAligned(size_t /*size*/, size_t /*alignment*/) {
    if (freeList_ >= objectCount_) return nullptr; // Pool exhausted

    size_t slotSize = (objectSize_ > sizeof(size_t)) ? objectSize_ : sizeof(size_t);
    slotSize = (slotSize + objectAlignment_ - 1) & ~(objectAlignment_ - 1);

    void* ptr = buffer_ + slotSize * freeList_;
    freeList_ = reinterpret_cast<size_t*>(ptr)[0]; // Next free index
    return ptr;
}

void PoolAllocator::deallocate(void* ptr) {
    if (!ptr) return;

    size_t slotSize = (objectSize_ > sizeof(size_t)) ? objectSize_ : sizeof(size_t);
    slotSize = (slotSize + objectAlignment_ - 1) & ~(objectAlignment_ - 1);

    size_t index = (static_cast<uint8_t*>(ptr) - buffer_) / slotSize;
    reinterpret_cast<size_t*>(ptr)[0] = freeList_;
    freeList_ = index;
}

void PoolAllocator::reset() {
    size_t slotSize = (objectSize_ > sizeof(size_t)) ? objectSize_ : sizeof(size_t);
    slotSize = (slotSize + objectAlignment_ - 1) & ~(objectAlignment_ - 1);

    for (size_t i = 0; i < objectCount_ - 1; ++i) {
        size_t* slot = reinterpret_cast<size_t*>(buffer_ + slotSize * i);
        *slot = i + 1;
    }
    size_t* lastSlot = reinterpret_cast<size_t*>(buffer_ + slotSize * (objectCount_ - 1));
    *lastSlot = objectCount_;
    freeList_ = 0;
}

size_t PoolAllocator::capacity() const {
    return objectSize_ * objectCount_;
}

size_t PoolAllocator::used() const {
    // Count allocated slots
    size_t allocated = 0;
    size_t idx = freeList_;
    while (idx < objectCount_) {
        size_t slotSize = (objectSize_ > sizeof(size_t)) ? objectSize_ : sizeof(size_t);
        slotSize = (slotSize + objectAlignment_ - 1) & ~(objectAlignment_ - 1);
        idx = reinterpret_cast<const size_t*>(buffer_ + slotSize * idx)[0];
        ++allocated;
    }
    return (objectCount_ - allocated) * objectSize_;
}

} // namespace lux
