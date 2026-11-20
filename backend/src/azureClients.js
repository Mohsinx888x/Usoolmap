import { DefaultAzureCredential } from '@azure/identity';
import { BlobServiceClient } from '@azure/storage-blob';
import { QueueServiceClient } from '@azure/storage-queue';

/**
 * Connection string when one is configured (local development, Azurite); otherwise the managed
 * identity of the container app through DefaultAzureCredential, so no storage key is needed.
 * @param {'blob' | 'queue'} service
 * @param {{storageConnectionString: string, storageAccount: string}} config
 */
export function storageServiceClient(service, { storageConnectionString, storageAccount }) {
  const Client = service === 'blob' ? BlobServiceClient : QueueServiceClient;
  if (storageConnectionString) return Client.fromConnectionString(storageConnectionString);
  return new Client(`https://${storageAccount}.${service}.core.windows.net`, new DefaultAzureCredential());
}
