# AI Tools through OpenWebUI

FinAssist keeps the Spring AI OpenAI client and calls OpenWebUI at
`https://ai2mee.ru/api/chat/completions`. OpenWebUI forwards requests to its configured
providers. Jmix executes application tools locally under the current user's security
context; this integration does not expose an MCP server or enable OpenWebUI tools.

## Configuration

| Variable          | Value                                                                    |
| ----------------- | ------------------------------------------------------------------------ |
| `OPENAI_BASE_URL` | `https://ai2mee.ru/api` (default)                                        |
| `OPENAI_API_KEY`  | OpenWebUI API key with access to the selected model                      |
| `OPENAI_MODEL`    | Required exact ID returned by `/api/models`; no guessed provider default |

The installed Spring AI 2.0.0 SDK appends `/chat/completions` to the base URL.
Do not append `/v1` or `/chat/completions` to the configured `/api` URL.
OpenAI and Anthropic models both use this gateway contract. Their presence in the
OpenWebUI UI does not guarantee that their adapter supports native tool calling.
Test each selected model before rollout. Prefer a base model without OpenWebUI
server-side tools, filters or custom system prompts until compatibility is verified.

Enable API keys and grant the key's account access to the selected models in OpenWebUI.
Store the key in the protected server environment, not Git, image layers or build args.
For local `bootRun`, export these variables in the launch environment; Docker's
`docker/.env` is not automatically loaded by Gradle. Keep local database settings.

## Verify from the production host

An administrator must run these checks from the production host (ideally also the
application container's network), with the three variables securely loaded into the
process environment. Do not paste the key in shell commands or enable shell tracing.
This Python example keeps the credential out of command-line arguments and outputs:

```bash
python3 - <<'PY'
import json, os, urllib.request
base = os.environ['OPENAI_BASE_URL'].rstrip('/')
headers = {'Authorization': 'Bearer ' + os.environ['OPENAI_API_KEY'],
           'Content-Type': 'application/json'}
def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)
models = request('/models')
ids = [item['id'] for item in models['data']]
print('Available model IDs:', ids)
model = os.environ['OPENAI_MODEL']
assert model in ids, 'Configured model is not available to this key'
reply = request('/chat/completions', {
    'model': model, 'stream': False,
    'messages': [{'role': 'user', 'content': 'Reply with OK'}]})
assert reply.get('choices'), 'Missing completion choices'
print('Chat completion received')
PY
```

A successful text completion is only the first check. In FinAssist, verify a data
question that invokes AI Tools, a follow-up question, entity navigation links and a
restricted user's access. Confirm the tool result reaches the model and the final
answer is returned. Verify streaming if used by the chat. Repeat for the alternate
provider model by changing `OPENAI_MODEL` and recreating the application container.
No automatic provider fallback or runtime model selector is introduced.

`401/403`: key/API permissions or model access. `404`: route or model ID.
`400`: inspect model compatibility with tool schemas and request parameters.
`502/503`, TLS errors or timeout: inspect the gateway, its provider connection and
reverse proxy. Never disable certificate verification. Keep prompt/body/header
logging off when checking production requests.

## Rollout and rollback

1. Back up the server `.env`, Compose file and current image tag/digest, with mode 600.
2. Update local `docker/.env`, then run
   `python3 scripts/sync-openai-secrets.py --repo vfadeevGit/fin-assist` to upload only AI settings
   to GitHub's `production` environment. Repeat after changing the key or model.
3. Once, upgrade the server deployment scripts and install `docker-compose.ai.yml` using
   the installer described in [CI-CD.md](CI-CD.md). Keep the existing server base Compose and database settings.
4. Deploy from the manual GitHub workflow. It passes the AI settings over SSH stdin and applies
   the fixed override. After a successful health check, the values are saved in the server `.env`.
5. Verify the UI scenarios above. A container restart alone does not pick up changed variables.

6. Confirm gateway access logs show `/api/chat/completions` requests from production.

For rollback, restore the backed-up image and matching environment/Compose settings,
then recreate the application service using both `docker-compose.yml` and `docker-compose.ai.yml`. Returning to OpenRouter restores the previous
configuration but does not resolve its geographic availability issue. Alternatively,
change to another already verified model on the same OpenWebUI host.

## Automated checks

`./gradlew test --tests '*OpenWebUiConnectionTest'` exercises the actual configured
Spring AI client with a local HTTP server: path, Bearer auth, model, tool round trip,
streaming, gateway errors and timeout. No real key or paid inference is used.
Full context tests require a disposable PostgreSQL database and placeholder AI variables;
CI supplies a loopback-only endpoint and a test model. These tests do not prove the
actual gateway's provider adapters or production network connectivity.

References: [OpenWebUI API](https://docs.openwebui.com/reference/api-endpoints/),
[API keys](https://docs.openwebui.com/features/authentication-access/api-keys/).
