// UsoolMap voice backend on Azure.
//
// NOT COMPILED AND NOT DEPLOYED: this file was written without access to the az or bicep CLI.
// Run `az bicep build` and `az deployment group what-if` (see README.md) before the first
// deployment and expect to fix small things.
//
// Secrets: the secure parameters below are written to Key Vault by this deployment and reach
// the container only as Key Vault references resolved through the managed identity. They are
// never stored in a parameter file. The Speech key and the database URL are derived here, so
// nobody has to copy them by hand.

targetScope = 'resourceGroup'

@description('Azure region for everything that has no parameter of its own.')
param location string = resourceGroup().location

@description('Short lower-case prefix for resource names.')
@minLength(3)
@maxLength(11)
param namePrefix string = 'usoolmap'

@description('Container image, e.g. myregistry.azurecr.io/usoolmap-voice:1')
param image string

@description('Name of an Azure Container Registry in this resource group that holds the image. Leave empty for a public image.')
param acrName string = ''

@description('Region of the Speech resource. Pick one that offers the neural voices you need.')
param speechLocation string = location

@description('Region of the PostgreSQL server (Burstable capacity is not offered in every region).')
param postgresLocation string = location

param postgresAdminLogin string = 'usoolmapadmin'

@secure()
param postgresAdminPassword string

@secure()
@description('xAI API key used for Grok text generation.')
param xaiApiKey string

@secure()
@description('Random secret that signs device tokens.')
param tokenSecret string

@secure()
@description('Key the Android app sends when a device registers.')
param appKey string

@secure()
@description('Key for the /v1/admin endpoints.')
param adminKey string

param grokModel string = 'latest'

@description('Daily generation budget in USD enforced by the application (estimate, see backend/README.md).')
param dailyBudgetUsd string = '5'

@minValue(1)
param maxReplicas int = 2

@description('Monthly Azure cost budget for this resource group, in the billing currency.')
param monthlyBudgetAmount int = 100

@description('Address that receives the budget alerts.')
param budgetContactEmail string

@description('First month of the budget (yyyy-MM). Keep the value of the first deployment: a budget start date cannot be changed later.')
param budgetStartMonth string = utcNow('yyyy-MM')

var suffix = uniqueString(resourceGroup().id)
var storageName = take(toLower(replace('${namePrefix}${suffix}', '-', '')), 24)
var keyVaultName = take('${namePrefix}-kv-${suffix}', 24)
var databaseName = 'usoolmap'
var blobContainerName = 'clips'
var queueName = 'voice-jobs'

// Built-in role definition ids.
var roles = {
  storageBlobDataContributor: 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
  storageQueueDataContributor: '974c5e8b-45b9-4653-ba55-5f855dd0fb88'
  keyVaultSecretsUser: '4633458b-17de-408a-b874-0445c86b69e6'
  acrPull: '7f951dda-4ed3-4680-a7ca-43fe172d538d'
}

// ---- logs and the Container Apps environment ----------------------------------------------------

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${namePrefix}-logs'
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
  }
}

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${namePrefix}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

// ---- identity ------------------------------------------------------------------------------

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${namePrefix}-identity'
  location: location
}

// ---- storage: clips container and job queue ------------------------------------------------------

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageName
  location: location
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    // The application authenticates with the managed identity, so account keys are switched off.
    allowSharedKeyAccess: false
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
}

resource clipsContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: blobContainerName
  properties: {
    publicAccess: 'None'
  }
}

resource queueService 'Microsoft.Storage/storageAccounts/queueServices@2023-05-01' = {
  parent: storage
  name: 'default'
}

resource jobsQueue 'Microsoft.Storage/storageAccounts/queueServices/queues@2023-05-01' = {
  parent: queueService
  name: queueName
}

resource blobRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: storage
  name: guid(storage.id, identity.id, roles.storageBlobDataContributor)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.storageBlobDataContributor)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource queueRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: storage
  name: guid(storage.id, identity.id, roles.storageQueueDataContributor)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.storageQueueDataContributor)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// ---- PostgreSQL --------------------------------------------------------------------------------

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: '${namePrefix}-pg-${suffix}'
  location: postgresLocation
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '16'
    administratorLogin: postgresAdminLogin
    administratorLoginPassword: postgresAdminPassword
    storage: {
      storageSizeGB: 32
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: postgres
  name: databaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// The 0.0.0.0 rule means "any Azure service", which is how the container app reaches the
// server over its public endpoint (TLS and the password still apply). For a stricter setup put
// the environment and the server in a virtual network; that is outside this template.
resource postgresAllowAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = {
  parent: postgres
  name: 'AllowAllAzureServicesAndResourcesWithinAzureIps'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

// ---- Azure AI Speech -----------------------------------------------------------------------

resource speech 'Microsoft.CognitiveServices/accounts@2023-05-01' = {
  name: '${namePrefix}-speech-${suffix}'
  location: speechLocation
  kind: 'SpeechServices'
  sku: {
    name: 'S0'
  }
  properties: {
    publicNetworkAccess: 'Enabled'
  }
}

// ---- Key Vault (RBAC) and the six secrets the container references ---------------------------------

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
  }
}

resource vaultRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: vault
  name: guid(vault.id, identity.id, roles.keyVaultSecretsUser)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.keyVaultSecretsUser)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource secretXaiApiKey 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'xai-api-key'
  properties: {
    value: xaiApiKey
  }
}

resource secretSpeechKey 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'azure-speech-key'
  properties: {
    value: speech.listKeys().key1
  }
}

resource secretTokenSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'token-secret'
  properties: {
    value: tokenSecret
  }
}

resource secretAppKey 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'app-key'
  properties: {
    value: appKey
  }
}

resource secretAdminKey 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'admin-key'
  properties: {
    value: adminKey
  }
}

resource secretDatabaseUrl 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'database-url'
  properties: {
    value: 'postgres://${postgresAdminLogin}:${uriComponent(postgresAdminPassword)}@${postgres.properties.fullyQualifiedDomainName}:5432/${databaseName}?sslmode=require'
  }
}

// ---- optional: pull the image from a registry in this resource group -------------------------------

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = if (!empty(acrName)) {
  name: acrName
}

resource acrPullRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(acrName)) {
  scope: registry
  name: guid(resourceGroup().id, acrName, identity.id, roles.acrPull)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.acrPull)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// ---- the container app: HTTP API and queue worker in one container ---------------------------------

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${namePrefix}-voice'
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identity.id}': {}
    }
  }
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8080
        transport: 'auto'
        allowInsecure: false
      }
      registries: empty(acrName) ? [] : [
        {
          // Assumes the public Azure cloud registry suffix.
          server: '${acrName}.azurecr.io'
          identity: identity.id
        }
      ]
      // Versionless secret URIs: a new secret version is picked up without redeploying.
      secrets: [
        {
          name: 'xai-api-key'
          keyVaultUrl: secretXaiApiKey.properties.secretUri
          identity: identity.id
        }
        {
          name: 'azure-speech-key'
          keyVaultUrl: secretSpeechKey.properties.secretUri
          identity: identity.id
        }
        {
          name: 'token-secret'
          keyVaultUrl: secretTokenSecret.properties.secretUri
          identity: identity.id
        }
        {
          name: 'app-key'
          keyVaultUrl: secretAppKey.properties.secretUri
          identity: identity.id
        }
        {
          name: 'admin-key'
          keyVaultUrl: secretAdminKey.properties.secretUri
          identity: identity.id
        }
        {
          name: 'database-url'
          keyVaultUrl: secretDatabaseUrl.properties.secretUri
          identity: identity.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'voice-backend'
          image: image
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            { name: 'ROLE', value: 'all' }
            { name: 'PORT', value: '8080' }
            { name: 'AZURE_STORAGE_ACCOUNT', value: storage.name }
            // Tells DefaultAzureCredential which user-assigned identity to use.
            { name: 'AZURE_CLIENT_ID', value: identity.properties.clientId }
            { name: 'BLOB_CONTAINER', value: blobContainerName }
            { name: 'QUEUE_NAME', value: queueName }
            { name: 'AZURE_SPEECH_REGION', value: speechLocation }
            { name: 'GROK_MODEL', value: grokModel }
            { name: 'DAILY_BUDGET_USD', value: dailyBudgetUsd }
            { name: 'XAI_API_KEY', secretRef: 'xai-api-key' }
            { name: 'AZURE_SPEECH_KEY', secretRef: 'azure-speech-key' }
            { name: 'TOKEN_SECRET', secretRef: 'token-secret' }
            { name: 'APP_KEY', secretRef: 'app-key' }
            { name: 'ADMIN_KEY', secretRef: 'admin-key' }
            { name: 'DATABASE_URL', secretRef: 'database-url' }
          ]
          probes: [
            {
              type: 'Liveness'
              httpGet: {
                path: '/healthz'
                port: 8080
              }
              periodSeconds: 30
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/healthz'
                port: 8080
              }
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        // One replica always on: the queue worker lives in the same container as the API.
        minReplicas: 1
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
  // The identity must be able to read the secrets, pull the image and reach the data before the
  // first revision starts; the database and its firewall rule must exist for the start-up migration.
  dependsOn: [
    vaultRole
    blobRole
    queueRole
    acrPullRole
    clipsContainer
    jobsQueue
    database
    postgresAllowAzure
  ]
}

// ---- cost monitoring -------------------------------------------------------------------------
// Covers the Azure resources in this resource group only. xAI usage is billed by xAI and is not
// visible here: watch it with GET /v1/admin/usage and in the xAI console.

resource budget 'Microsoft.Consumption/budgets@2023-11-01' = {
  name: '${namePrefix}-monthly-budget'
  properties: {
    category: 'Cost'
    amount: monthlyBudgetAmount
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: '${budgetStartMonth}-01'
    }
    notifications: {
      actualOver80Percent: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 80
        thresholdType: 'Actual'
        contactEmails: [
          budgetContactEmail
        ]
      }
      forecastOver100Percent: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        thresholdType: 'Forecasted'
        contactEmails: [
          budgetContactEmail
        ]
      }
    }
  }
}

output appUrl string = 'https://${app.properties.configuration.ingress.fqdn}'
output keyVaultName string = vault.name
output storageAccountName string = storage.name
output postgresServerName string = postgres.name
output speechAccountName string = speech.name
