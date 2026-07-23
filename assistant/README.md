# Flumen Assistant

The assistant is optional. The core Flumen stack does not depend on these services.

Required secrets must be supplied through the environment; none are exposed to the
browser:

- `FLUMEN_ASSISTANT_SERVICE_TOKEN` (also configured on the backend)
- `ASSISTANT_TRIGGER_SECRET_KEY`
- `ASSISTANT_TRIGGER_ACCESS_TOKEN`
- `GROQ_API_KEY`
- `GEMINI_API_KEY`
- datastore, session, encryption, provider, coordinator, and managed-worker secrets referenced by
  `docker-compose.yaml`

Set `ASSISTANT_TRIGGER_PUBLIC_URL` to the externally reachable Trigger URL. It must
use HTTPS whenever Flumen is served over HTTPS.

The web app bootstraps a worker token into the private
`assistant-trigger-shared` volume; the supervisor reads that token directly.

```bash
docker compose --profile assistant up -d
docker compose --profile assistant --profile assistant-dev up trigger-dev
```

The task uses only the allowlisted `/api/analytics/investigation` endpoints.
Groq handles normal requests; Gemini is attempted only for rate limits, timeouts,
connection failures, or provider 5xx responses.
