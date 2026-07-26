## Docker Compose Files

Use `docker-compose.install.yaml` when you want the packaged application:

```bash
docker compose -f docker-compose.install.yaml up -d
```

That stack starts nginx, backend, frontend, simulator, and the infrastructure services.

Use `docker-compose.dev.yaml` when you run the backend and frontend locally:

```bash
docker compose -f docker-compose.dev.yaml up -d
```

That stack starts OrientDB, ClickHouse, RabbitMQ, Redis, Redis Commander, Vector, and the local Trigger.dev services. It does not start backend, frontend, or nginx. Its Flumen infrastructure ports and credentials match `backend/src/main/resources/application.properties`.

Then start the apps from the host:

```bash
cd backend
./mvnw spring-boot:run
cd ../frontend
pnpm run dev
```

If rabbit has permissions problems:

sudo chown -R 999:999 ./rabbitmq
