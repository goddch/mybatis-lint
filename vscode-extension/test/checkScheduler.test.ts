import assert from 'node:assert/strict';
import { test } from 'node:test';

import { CheckScheduler, isRelevantForCheck } from '../src/checkScheduler';

/** Manual timers so the tests control when the debounce delay elapses. */
function fakeTimers() {
  let nextId = 1;
  const callbacks = new Map<number, () => void>();
  return {
    timers: {
      setTimeout: ((callback: () => void) => {
        const id = nextId++;
        callbacks.set(id, callback);
        return id;
      }) as unknown as typeof setTimeout,
      clearTimeout: ((id: number) => {
        callbacks.delete(id);
      }) as unknown as typeof clearTimeout,
    },
    fireAll() {
      const pending = [...callbacks.values()];
      callbacks.clear();
      pending.forEach((callback) => callback());
    },
    get size() {
      return callbacks.size;
    },
  };
}

/** Lets queued promise callbacks run. */
function flush(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

test('collapses requests made within the delay into one run', async () => {
  const clock = fakeTimers();
  let runs = 0;
  const scheduler = new CheckScheduler(async () => {
    runs++;
  }, 500, clock.timers);

  scheduler.request();
  scheduler.request();
  scheduler.request();
  assert.equal(clock.size, 1);

  clock.fireAll();
  await flush();
  assert.equal(runs, 1);
});

test('runs once more after the current run when a request arrives meanwhile', async () => {
  const clock = fakeTimers();
  let runs = 0;
  let finishFirst: () => void = () => {};
  const scheduler = new CheckScheduler(() => {
    runs++;
    if (runs === 1) {
      return new Promise<void>((resolve) => {
        finishFirst = resolve;
      });
    }
    return Promise.resolve();
  }, 500, clock.timers);

  scheduler.request();
  clock.fireAll();
  await flush();
  assert.equal(runs, 1);

  // Two more saves while the first run is still going: only one follow-up run.
  scheduler.request();
  clock.fireAll();
  scheduler.request();
  clock.fireAll();
  await flush();
  assert.equal(runs, 1);

  finishFirst();
  await flush();
  await flush();
  assert.equal(runs, 2);
});

test('keeps working after a run fails', async () => {
  const clock = fakeTimers();
  let runs = 0;
  const scheduler = new CheckScheduler(async () => {
    runs++;
    if (runs === 1) {
      throw new Error('boom');
    }
  }, 500, clock.timers);

  scheduler.request();
  clock.fireAll();
  await flush();
  scheduler.request();
  clock.fireAll();
  await flush();
  assert.equal(runs, 2);
});

test('dispose cancels a request that has not started', async () => {
  const clock = fakeTimers();
  let runs = 0;
  const scheduler = new CheckScheduler(async () => {
    runs++;
  }, 500, clock.timers);

  scheduler.request();
  scheduler.dispose();
  clock.fireAll();
  await flush();
  assert.equal(runs, 0);
});

test('only mapper XML and Java files trigger a check', () => {
  assert.equal(isRelevantForCheck('/w/src/UserMapper.xml'), true);
  assert.equal(isRelevantForCheck('C:\\w\\User.JAVA'), true);
  assert.equal(isRelevantForCheck('/w/README.md'), false);
  assert.equal(isRelevantForCheck('/w/settings.json'), false);
});
