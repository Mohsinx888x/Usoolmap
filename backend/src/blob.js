import { Readable } from 'node:stream';
import { storageServiceClient } from './azureClients.js';

/**
 * @typedef {object} BlobStore
 * @property {() => Promise<void>} init creates the container when missing
 * @property {(path: string, data: Buffer, contentType: string) => Promise<void>} put
 * @property {(path: string, offset?: number, count?: number) => Promise<import('node:stream').Readable | null>} read
 *   stream of `count` bytes from `offset` (to the end when omitted); null when the blob is missing
 */

/** @returns {BlobStore} */
export function createAzureBlobStore(config) {
  const container = storageServiceClient('blob', config).getContainerClient(config.blobContainer);
  return {
    init: async () => void (await container.createIfNotExists()),
    async put(path, data, contentType) {
      await container.getBlockBlobClient(path).uploadData(data, { blobHTTPHeaders: { blobContentType: contentType } });
    },
    async read(path, offset = 0, count) {
      try {
        const download = await container.getBlockBlobClient(path).download(offset, count);
        return /** @type {Readable} */ (download.readableStreamBody);
      } catch (error) {
        if (error.statusCode === 404) return null;
        throw error;
      }
    },
  };
}

/** @returns {BlobStore & {blobs: Map<string, {data: Buffer, contentType: string}>}} */
export function createMemoryBlobStore() {
  const blobs = new Map();
  return {
    blobs,
    init: async () => {},
    put: async (path, data, contentType) => void blobs.set(path, { data: Buffer.from(data), contentType }),
    async read(path, offset = 0, count) {
      const blob = blobs.get(path);
      if (!blob) return null;
      return Readable.from([blob.data.subarray(offset, count === undefined ? undefined : offset + count)]);
    },
  };
}
