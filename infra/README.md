# Deploying the UsoolMap voice backend to Azure

> **Not compiled, not deployed.** `main.bicep` was written on a machine without the `az` or
> `bicep` CLI. It has never been compiled, validated or deployed. Steps 4 and 5 below (build and
> what-if) are the first real check; expect to fix small things such as an API version or a
> property name. Region availability (Container Apps, Speech with the voices you need,
> PostgreSQL Burstable) was not checked either.

## What gets created

One resource group containing: Log Analytics workspace, Container Apps environment,
user-assigned managed identity, Key Vault (RBAC), Storage account (private blob container
`clips`, queue `voice-jobs`, account keys disabled), Azure Database for PostgreSQL flexible server
(Burstable B1ms) with database `usoolmap`, Azure AI Speech (S0), one Container App (external
HTTPS ingress → port 8080, at least one replica, API and worker in the same container), and a
monthly cost budget with e-mail alerts at 80 % actual and 100 % forecast.

The identity gets Storage Blob Data Contributor, Storage Queue Data Contributor, Key Vault
Secrets User and, when `acrName` is set, AcrPull.

**Secrets.** Key Vault holds `xai-api-key`, `azure-speech-key`, `token-secret`, `app-key`,
`admin-key` and `database-url`. The container sees them as environment variables through Key Vault
references. The deployment writes them: four come from secure parameters you pass on the command
line, the Speech key and the database URL are derived inside the template. No secret belongs in
`main.parameters.json` or in git.

## Steps

You need the Azure CLI, and the **Owner** role (or Contributor plus User Access Administrator) on
the resource group, because the template creates role assignments.

### 1. Sign in and choose names

```sh
az login
az account set --subscription "<subscription name or id>"

RG=usoolmap-voice
LOCATION=qatarcentral            # any region that offers everything listed above
ACR=usoolmapacr$RANDOM           # must be globally unique, letters and digits only
echo "registry: $ACR"            # note it: you need the same name for every later deployment

for ns in Microsoft.App Microsoft.OperationalInsights Microsoft.ContainerRegistry Microsoft.KeyVault \
          Microsoft.Storage Microsoft.DBforPostgreSQL Microsoft.CognitiveServices Microsoft.Consumption; do
  az provider register --namespace $ns --wait
done
```

### 2. Resource group and registry

```sh
az group create --name $RG --location $LOCATION
az acr create --resource-group $RG --name $ACR --sku Basic
```

### 3. Build and push the image

The build runs in Azure, so no local Docker is needed:

```sh
az acr build --registry $ACR --image usoolmap-voice:1 ../backend
```

(`az containerapp up --source ../backend` is the quick alternative, but it creates its own
environment and no Key Vault, database or budget, so it does not fit this template.)

### 4. Parameters and secrets

```sh
cp main.parameters.example.json main.parameters.json
# edit main.parameters.json: location(s), image "<ACR>.azurecr.io/usoolmap-voice:1",
# acrName "<ACR>", budgetContactEmail, monthlyBudgetAmount

TOKEN_SECRET=$(openssl rand -base64 48)
APP_KEY=$(openssl rand -hex 24)
ADMIN_KEY=$(openssl rand -hex 32)
PG_PASSWORD=$(openssl rand -base64 30)
read -r -s -p "xAI API key: " XAI_API_KEY; echo
```

Keep `APP_KEY` (it goes into the Android app) and `ADMIN_KEY` (for the admin calls below) in a
password manager. The others can always be read back from Key Vault.

### 5. Check, then deploy

```sh
az bicep build --file main.bicep        # first ever compile of this file

SECRETS="xaiApiKey=$XAI_API_KEY tokenSecret=$TOKEN_SECRET appKey=$APP_KEY adminKey=$ADMIN_KEY postgresAdminPassword=$PG_PASSWORD"

az deployment group what-if --resource-group $RG --template-file main.bicep \
  --parameters @main.parameters.json --parameters $SECRETS

az deployment group create --resource-group $RG --name voice-backend --template-file main.bicep \
  --parameters @main.parameters.json --parameters $SECRETS
```

If the container app fails on the very first run with a Key Vault or registry permission error,
run the same `create` command again: a new role assignment can take a few minutes to take effect.

On the first start the container creates the database schema itself.

### 6. Get the URL and check it

```sh
URL=$(az deployment group show -g $RG -n voice-backend --query properties.outputs.appUrl.value -o tsv)
echo $URL
curl -s $URL/healthz                     # {"ok":true}
```

If it does not answer:

```sh
az containerapp logs show --resource-group $RG --name usoolmap-voice --tail 100
```

A line `Missing required settings: ...` names any setting that did not arrive.

### 7. Fill the library

The library starts empty and the starter pack is empty until clips exist. Queue a first batch
(14 event types × `perEvent` clips) for each language, personality and humour level you ship:

```sh
curl -s -X POST $URL/v1/admin/pregenerate -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"language":"ar-QA","personality":"abu-nasser","humourLevel":2,"perEvent":3}'

curl -s "$URL/v1/admin/usage?days=1" -H "X-Admin-Key: $ADMIN_KEY"     # watch the counters grow
```

Generation stops for the day when the estimated spend reaches `dailyBudgetUsd`. Before relying on
that estimate, set `PRICE_GROK_PER_CALL_USD` and `PRICE_TTS_PER_MILLION_CHARS_USD` on the container
app from your real price sheets (the built-in values are placeholders):

```sh
az containerapp update -g $RG -n usoolmap-voice \
  --set-env-vars PRICE_GROK_PER_CALL_USD=<your price> PRICE_TTS_PER_MILLION_CHARS_USD=<your price>
```

Then have a native speaker listen before users do: `backend/scripts/voice-qa.js`
(see `backend/README.md`). A regional voice gives the accent only; whether the clips sound like
real colloquial speech is something only a listener can tell.

### 8. Put the URL into the Android app

In the app project's `local.properties` (never committed):

```properties
VOICE_BACKEND_URL=<the URL from step 6>
VOICE_BACKEND_APP_KEY=<APP_KEY from step 4>
```

Rebuild the app. The app key is compiled into the APK and can be extracted from it; it only
allows registering a device, and every device is rate limited and has a daily quota.

## Later changes

- **New image**: `az acr build --registry $ACR --image usoolmap-voice:2 ../backend`, then
  `az containerapp update -g $RG -n usoolmap-voice --image $ACR.azurecr.io/usoolmap-voice:2`
  (and update `image` in `main.parameters.json`).
- **Redeploying the template** writes the secret parameters to Key Vault again, so pass the
  current values (read them with `az keyvault secret show --vault-name <vault> --name token-secret
  --query value -o tsv`; you need the Key Vault Secrets Officer role on the vault for that).
  Passing a different `tokenSecret` signs every phone out until it registers again.
- **Rotating one secret**: `az keyvault secret set --vault-name <vault> --name xai-api-key --value ...`
  then restart the revision (`az containerapp revision restart`). The references are versionless.
- **Budget start date**: after the first deployment add `"budgetStartMonth": { "value": "yyyy-MM" }`
  (the month of that first deployment) to `main.parameters.json`; Azure does not allow changing
  the start date of an existing budget.
- **Deleting and recreating**: Key Vault and Speech resources are soft-deleted. Purge them
  (`az keyvault purge`, `az cognitiveservices account purge`) before reusing the same names.

## Things to know

- The Azure budget only sees Azure costs in this resource group. xAI usage is billed by xAI:
  watch `GET /v1/admin/usage` and the xAI console. The budget alerts; it does not stop anything.
- PostgreSQL is reachable from any Azure service (firewall rule `0.0.0.0`), protected by TLS and
  the password. Private networking is not part of this template.
- The database URL uses `sslmode=require`; the connection from the container to Azure Database
  for PostgreSQL was not tested.
- The database admin user is also the application user.
