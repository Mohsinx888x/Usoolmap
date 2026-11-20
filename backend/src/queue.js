import { storageServiceClient } from './azureClients.js';

/**
 * @typedef {object} ReceivedMessage
 * @property {any} body the object passed to send()
 * @property {number} attempts how many times this message has been received, this time included
 * @property {() => Promise<void>} ack deletes the message; without it the message comes back
 *   after the visibility timeout
 *
 * @typedef {object} JobQueue
 * @property {() => Promise<void>} init creates the queue when missing
 * @property {(body: any) => Promise<void>} send
 * @property {(visibilityTimeoutSeconds: number) => Promise<ReceivedMessage | null>} receive
 */

/** @returns {JobQueue} */
export function createAzureJobQueue(config) {
  const queue = storageServiceClient('queue', config).getQueueClient(config.queueName);
  return {
    init: async () => void (await queue.createIfNotExists()),
    send: async (body) => void (await queue.sendMessage(JSON.stringify(body))),
    async receive(visibilityTimeoutSeconds) {
      const { receivedMessageItems } = await queue.receiveMessages({
        numberOfMessages: 1,
        visibilityTimeout: visibilityTimeoutSeconds,
      });
      const [message] = receivedMessageItems;
      if (!message) return null;
      return {
        body: JSON.parse(message.messageText),
        attempts: message.dequeueCount,
        ack: async () => void (await queue.deleteMessage(message.messageId, message.popReceipt)),
      };
    },
  };
}

/**
 * Same semantics as the storage queue, in memory: a received message is hidden for the
 * visibility timeout and reappears with a higher attempt count unless it was acknowledged.
 * @param {{now?: () => number}} [options]
 * @returns {JobQueue & {size: () => number}}
 */
export function createMemoryJobQueue({ now = Date.now } = {}) {
  /** @type {{body: string, attempts: number, visibleAt: number}[]} */
  let messages = [];
  return {
    size: () => messages.length,
    init: async () => {},
    send: async (body) => void messages.push({ body: JSON.stringify(body), attempts: 0, visibleAt: 0 }),
    async receive(visibilityTimeoutSeconds) {
      const message = messages.find((m) => m.visibleAt <= now());
      if (!message) return null;
      message.attempts += 1;
      message.visibleAt = now() + visibilityTimeoutSeconds * 1000;
      return {
        body: JSON.parse(message.body),
        attempts: message.attempts,
        ack: async () => void (messages = messages.filter((m) => m !== message)),
      };
    },
  };
}
