/**
 * Decides when a save-triggered check actually runs.
 *
 * Saving several files in a row (or "Save All") should not start one analyzer
 * process per file: requests are debounced, and a request that arrives while a
 * check is running is folded into a single follow-up run instead of running
 * concurrently. Concurrent runs would race to publish diagnostics, and the
 * slower, older one could win.
 */
export class CheckScheduler {
  private timer: ReturnType<typeof setTimeout> | undefined;
  private running = false;
  private pending = false;

  constructor(
    private readonly run: () => Promise<void>,
    private readonly delayMs: number,
    private readonly timers: Pick<typeof globalThis, 'setTimeout' | 'clearTimeout'> = globalThis,
  ) {}

  /** Asks for a check soon. Repeated requests within the delay collapse into one. */
  request(): void {
    if (this.timer !== undefined) {
      this.timers.clearTimeout(this.timer);
    }
    this.timer = this.timers.setTimeout(() => {
      this.timer = undefined;
      void this.start();
    }, this.delayMs);
  }

  /** Cancels a check that has been requested but not started yet. */
  dispose(): void {
    if (this.timer !== undefined) {
      this.timers.clearTimeout(this.timer);
      this.timer = undefined;
    }
    this.pending = false;
  }

  private async start(): Promise<void> {
    if (this.running) {
      this.pending = true;
      return;
    }
    this.running = true;
    try {
      await this.run();
    } catch {
      // The run reports its own failures; a failed run must not stop later ones.
    } finally {
      this.running = false;
    }
    if (this.pending) {
      this.pending = false;
      await this.start();
    }
  }
}

/** Saving these files can change the analyzer's result. */
export function isRelevantForCheck(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  return lower.endsWith('.xml') || lower.endsWith('.java');
}
