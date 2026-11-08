# OpenWebUI integration implementation plan

Goal: route existing Jmix AI Tools through https://ai2mee.ru instead of OpenRouter.
Architecture: keep Spring AI's OpenAI client and Jmix tool execution; use OpenWebUI as the model gateway. No MCP endpoint or UI redesign is required.
Stack verified: Jmix 3.1.0, Spring AI 2.0.0, Java 21.
Spec: user-approved six-step plan in this session (2026-11-08).

- [x] Inspect current configuration, dependencies and gateway reachability.
- [x] Verify the HTTP contract with an isolated server: configurable URL/key/model, tool round trip, streaming, errors and timeout.
- [x] Configure OPENAI_BASE_URL (default https://ai2mee.ru/api), OPENAI_API_KEY and required OPENAI_MODEL.
- [x] Update production/smoke Compose, CI placeholders and deployment instructions.
- [x] Inspect modified files, run full tests with a disposable PostgreSQL database, review changes.
- [ ] Verify authenticated model discovery and selected OpenAI/Anthropic models against the real gateway.
- [ ] Verify production reachability and AI Tools after rollout.

## Decisions and execution evidence

- Work in the user-requested feature/openwebui-integration checkout; preserve pre-existing local changes.
- The previously missing Jmix toolkit skill was found in the neighbouring jmix-crm project and read before editing.
- Gradle dependencyInsight confirms Spring AI 2.0.0. Its SDK accepts a base URL including /api; test the resulting /api/chat/completions path rather than using obsolete completions-path properties.
- Unauthenticated GET https://ai2mee.ru/api/models returns HTTP 401 over HTTPS from this machine. This does not establish connectivity from production.
- No OpenWebUI key is present in the process environment or the project's dotenv files. Requested a secure key location, selected model and production access details.
- OPENAI_MODEL is required instead of guessing a model ID exposed by this particular gateway.
- Local HTTP tests must assert the effective base URL before sending a request, preventing unintended live requests if configuration regresses.
- Existing production deployment does not upload Compose or .env: server configuration must be updated separately before rollout.

- Initial HTTP tests failed on the hardcoded OpenRouter base URL before configuration changes.
- Spring AI 2.0 delegates tool execution to ToolCallingAdvisor. The HTTP test uses Jmix ChatClientFactory with its default advisors, matching production.
- All three Compose variants validated; 10 deployment-script tests passed.
- IDE inspections completed for all changed files: no errors; existing experimental Jmix API warnings in AiToolsConfigurationTest and pre-existing Markdown table formatting warnings in CI-CD.md.
- Independent read-only review found no actionable defects.
- Full clean test: 190 tests, zero failures/errors/skips, using disposable PostgreSQL on port 55439; container removed afterward.
- Final HTTP suite after deprecation cleanup: 8 tests passed. No live authenticated gateway or production rollout performed; credentials/model/access details remain pending.

## Deployment configuration transfer (follow-up)

- User requested automatic propagation of local OpenAI settings on deployment.
- Added local sync command for exactly three GitHub production secrets, SSH stdin JSON transport, strict server allowlist, and an installed AI-only Compose override.
- Deployment preserves database settings and writes AI settings to the server dotenv only after a successful health check.
- Uploaded OPENAI_BASE_URL, OPENAI_API_KEY and OPENAI_MODEL from local docker/.env into GitHub environment production (values not logged).
- 15 Python tests pass; production Compose and AI-only export validated with local settings. IDE inspections report no code errors.
- Independent review identified a first-install validation dependency on missing AI variables; fixed by using non-persisted placeholders during installer checks.
- One-time server script/override upgrade completed on VPS 2.59.42.251. Backup is in /root/finassist-ai-setup.TugBV3/backup. Installed files matched the uploaded archive; sudoers validated; running container IDs and server dotenv remained unchanged.
