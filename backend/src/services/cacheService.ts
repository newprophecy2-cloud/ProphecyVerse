interface CacheEntry<T> {
  value: T;
  expiresAt: number;
}

export class MemoryCacheService {
  private readonly store = new Map<string, CacheEntry<unknown>>();
  private readonly inflight = new Map<string, Promise<unknown>>();
  private readonly maxEntries = 500;

  public get<T>(key: string): T | null {
    const entry = this.store.get(key);
    if (!entry) return null;
    if (Date.now() > entry.expiresAt) {
      this.store.delete(key);
      return null;
    }
    return entry.value as T;
  }

  public set<T>(key: string, value: T, ttlMs: number): void {
    if (ttlMs <= 0) return;
    if (this.store.size >= this.maxEntries) {
      const oldestKey = this.store.keys().next().value;
      if (oldestKey !== undefined) {
        this.store.delete(oldestKey);
      }
    }
    this.store.set(key, {
      value,
      expiresAt: Date.now() + ttlMs,
    });
  }

  public async getOrFetch<T>(key: string, ttlMs: number, fetcher: () => Promise<T>): Promise<T> {
    const cached = this.get<T>(key);
    if (cached !== null) {
      return cached;
    }

    const existing = this.inflight.get(key);
    if (existing) {
      return existing as Promise<T>;
    }

    const promise = fetcher()
      .then((res) => {
        this.set(key, res, ttlMs);
        return res;
      })
      .finally(() => {
        this.inflight.delete(key);
      });

    this.inflight.set(key, promise);
    return promise;
  }

  public clear(): void {
    this.store.clear();
    this.inflight.clear();
  }
}

export const cacheService = new MemoryCacheService();
