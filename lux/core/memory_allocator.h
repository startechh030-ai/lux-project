#ifndef LUX_CORE_MEMORY_ALLOCATOR_H
#define LUX_CORE_MEMORY_ALLOCATOR_H

#include <cstddef>
#include <cstdint>

namespace lux {

/// Alignment guaranteed by all Lux allocators (16 bytes for NEON/SSE).
constexpr size_t kDefaultAlignment = 16;

/// Abstract interface for a memory allocator.
/// Implementations: LinearAllocator, PoolAllocator, StackAllocator.
class Allocator {
public:
    virtual ~Allocator() = default;

    /// Allocate `size` bytes with default alignment.
    virtual void* allocate(size_t size) = 0;

    /// Allocate `size` bytes with specified `alignment`.
    virtual void* allocateAligned(size_t size, size_t alignment) = 0;

    /// Return memory previously returned by allocate().
    virtual void deallocate(void* ptr) = 0;

    /// Reset the allocator (frees all memory at once — use with linear/stack allocators).
    virtual void reset() = 0;

    /// Returns total capacity in bytes.
    virtual size_t capacity() const = 0;

    /// Returns currently used bytes.
    virtual size_t used() const = 0;
};

/// A fast linear (bump) allocator. Deallocate is a no-op; call reset() to free all.
class LinearAllocator final : public Allocator {
public:
    explicit LinearAllocator(size_t capacity);
    ~LinearAllocator() override;

    LinearAllocator(const LinearAllocator&) = delete;
    LinearAllocator& operator=(const LinearAllocator&) = delete;
    LinearAllocator(LinearAllocator&& other) noexcept;
    LinearAllocator& operator=(LinearAllocator&& other) noexcept;

    void* allocate(size_t size) override;
    void* allocateAligned(size_t size, size_t alignment) override;
    void deallocate(void* ptr) override;
    void reset() override;
    size_t capacity() const override { return capacity_; }
    size_t used() const override;

private:
    uint8_t* buffer_ = nullptr;
    size_t capacity_ = 0;
    size_t offset_ = 0;
    bool ownsMemory_ = true;
};

/// A fixed-size pool allocator for objects of identical size.
class PoolAllocator final : public Allocator {
public:
    PoolAllocator(size_t objectSize, size_t objectAlignment, size_t objectCount);
    ~PoolAllocator() override;

    PoolAllocator(const PoolAllocator&) = delete;
    PoolAllocator& operator=(const PoolAllocator&) = delete;

    void* allocate(size_t size) override;
    void* allocateAligned(size_t size, size_t alignment) override;
    void deallocate(void* ptr) override;
    void reset() override;

    size_t capacity() const override;
    size_t used() const override;

private:
    uint8_t* buffer_ = nullptr;
    size_t objectSize_;
    size_t objectAlignment_;
    size_t objectCount_;
    size_t freeList_ = 0;       ///< Index of first free slot
    size_t* nextFree_ = nullptr; ///< Linked-list of free slots (stores indices)
};

} // namespace lux

#endif // LUX_CORE_MEMORY_ALLOCATOR_H
