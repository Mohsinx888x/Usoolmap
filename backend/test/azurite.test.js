// blob.js and queue.js against the Azurite storage emulator on localhost. Azurite is started
// here as a child process with in-memory persistence; if it does not come up (for example the
// ports are taken) the tests are reported as skipped, not passed.

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import net from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { buffer } from 'node:stream/consumers';
import { setTimeout as delay } from 'node:timers/promises';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

// The emulator is plain HTTP on localhost; it must never be sent through an outbound proxy.
process.env.NO_PROXY = [process.env.NO_PROXY, '127.0.0.1', 'localhost'].filter(Boolean).join(',');
const { createAzureBlobStore } = await import('../src/blob.js');
const { createAzureJobQueue } = await import('../src/queue.js');

const AZURITE = fileURLToPath(new URL('../node_modules/azurite/dist/src/azurite.js', import.meta.url));
const config = {
  storageConnectionString: 'UseDevelopmentStorage=true', // the well-known development account on 127.0.0.1:10000/10001
  storageAccount: '',
  blobContainer: `clips-${randomUUID().slice(0, 8)}`,
  queueName: `voice-jobs-${randomUUID().slice(0, 8)}`,
};

const portOpen = (port) =>
  new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    socket.once('connect', () => resolve(socket.end() && true));
    socket.once('error', () => resolve(false));
  });

async function startAzurite() {
  if ((await portOpen(10000)) || (await portOpen(10001))) return { reason: 'ports 10000/10001 are already in use' };
  const child = spawn(
    process.execPath,
    [AZURITE, '--inMemoryPersistence', '--silent', '--skipApiVersionCheck'],
    { stdio: 'ignore', cwd: workDir }, // Azurite writes its debug files to the working directory
  );
  let exited = false;
  child.once('exit', () => (exited = true));
  for (let waited = 0; waited < 30_000 && !exited; waited += 200) {
    if ((await portOpen(10000)) && (await portOpen(10001))) return { child };
    await delay(200);
  }
  child.kill();
  return { reason: exited ? 'the Azurite process exited at start' : 'Azurite did not start within 30 seconds' };
}

const workDir = await mkdtemp(path.join(tmpdir(), 'azurite-'));
const azurite = await startAzurite();
const skip = azurite.child ? false : `Azurite is not running: ${azurite.reason}`;

describe('Azure Storage implementations against Azurite', { skip }, () => {
  const blobs = createAzureBlobStore(config);
  const queue = createAzureJobQueue(config);

  before(() => Promise.all([blobs.init(), queue.init()]));

  test('init is idempotent', async () => {
    await blobs.init();
    await queue.init();
  });

  test('blob: put, full read, ranged reads, missing blob', async () => {
    const data = Buffer.from(Array.from({ length: 5000 }, (_, i) => i % 251));
    const blobPath = `clips/ar-QA/reroute/${randomUUID()}.mp3`;
    await blobs.put(blobPath, data, 'audio/mpeg');

    assert.ok((await buffer(await blobs.read(blobPath))).equals(data));
    assert.ok((await buffer(await blobs.read(blobPath, 1000))).equals(data.subarray(1000)));
    assert.ok((await buffer(await blobs.read(blobPath, 10, 20))).equals(data.subarray(10, 30)));
    assert.equal(await blobs.read(`clips/ar-QA/reroute/${randomUUID()}.mp3`), null);

    await blobs.put(blobPath, Buffer.from('replaced'), 'audio/mpeg');
    assert.equal((await buffer(await blobs.read(blobPath))).toString(), 'replaced');
  });

  test('queue: send, receive with attempt count, visibility timeout, ack', async () => {
    assert.equal(await queue.receive(1), null);
    const body = { jobId: randomUUID(), note: 'نص عربي & <xml>' };
    await queue.send(body);

    const first = await queue.receive(1);
    assert.deepEqual(first.body, body);
    assert.equal(first.attempts, 1);
    assert.equal(await queue.receive(1), null, 'hidden while the visibility timeout runs');

    await delay(1500);
    const second = await queue.receive(30);
    assert.deepEqual(second.body, body);
    assert.equal(second.attempts, 2, 'redelivered with a higher attempt count');
    await second.ack();
    assert.equal(await queue.receive(1), null, 'gone once acknowledged');
  });
});

after(async () => {
  azurite.child?.kill();
  await rm(workDir, { recursive: true, force: true });
});
