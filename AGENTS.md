# AGENTS.md - Agent Coding Guidelines

This file provides build commands and code style guidelines for agentic coding agents working in the Flunav repository.

## Build, Lint, and Test Commands

### Frontend (React + TypeScript + Vite)
```bash
cd frontend
pnpm install                    # Install dependencies
pnpm dev                       # Start development server
pnpm build                     # Build for production
pnpm lint                      # Run ESLint
pnpm generate-client            # Generate API client from live backend (http://localhost:8080/api-docs)
pnpm generate-client-local      # Generate API client from local ./openapi.json
```

### Backend (Java + Spring Boot)
```bash
cd backend
./mvnw clean install           # Build backend (including commons module dependency)
./mvnw clean install -DskipTests  # Build without tests
./mvnw test                    # Run all tests
./mvnw test -Dtest=BackendApplicationTests  # Run single test class
./mvnw test -Dtest=ClassName#methodName      # Run single test method
./mvnw jib:dockerBuild         # Build Docker image
```

### Commons Module (Java - Shared Events)
```bash
cd commons
./mvnw clean install           # Build and install to local Maven repo
```

### OPC Gateway & Simulator (Java)
```bash
cd opc-gateway
./mvnw clean install           # Build OPC gateway

cd simulator
./mvnw clean install           # Build simulator
```

### Docker Full Stack
```bash
docker-compose up -d           # Start all services (frontend, backend, databases, etc.)
docker-compose down            # Stop all services
```

## Java Code Style Guidelines

### Package Structure
```
com.flunav.backend.{domain|controllers|services|repositories|entities|models|utils|exception|config}
```
Follow standard Spring Boot layering: Controller → Service → Repository → Database

### Naming Conventions
- Classes: PascalCase (e.g., `ItemController`, `ItemService`)
- Methods/Variables: camelCase (e.g., `getAllItems`, `updatePosition`)
- Constants: UPPER_SNAKE_CASE (e.g., `PATH_TTL_MINUTES`)
- Event classes: End with `Event` (e.g., `ItemCreatedEvent`)
- Test classes: End with `Tests` (e.g., `BackendApplicationTests`)

### Annotations & Lombok
- Use `@Getter`/`@Setter` for DTOs and domain objects
- Use `@Service` for business logic, `@Repository` for data access, `@RestController` for endpoints
- Use `@ControllerAdvice` for global exception handling
- Use `@Component` for utility classes and helpers
- Use `@JsonCreator` and `@JsonProperty` on event DTOs in commons module

### Event Sourcing Pattern (Commons Module)
- Events are immutable and defined in `commons/src/main/java/flunav/events/`
- Use `@JsonCreator` for constructors to support Jackson deserialization
- Events extend base classes and include `entityId` and `eventType`
- No business logic in event classes - they are data carriers

### Error Handling
- Centralize exception handling in `GlobalExceptionHandler`
- Use appropriate HTTP status codes: BAD_REQUEST, NOT_FOUND, INTERNAL_SERVER_ERROR
- Log errors with SLF4J: `logger.error()` for errors, `logger.warn()` for client issues
- Return standardized error response with timestamp, status, message, and exception type

### Logging
- SLF4J Logger: `private static final Logger logger = LoggerFactory.getLogger(ClassName.class);`
- Use appropriate levels: error (exceptions), warn (client issues), info (normal flow), debug (diagnostic)
- Log with context: include IDs and relevant data in log messages

### Imports
Organize in three groups:
1. Standard Java/JavaX imports
2. Third-party imports (Spring, Lombok, Jackson, OrientDB, etc.)
3. Internal `flunav` package imports

### Type Safety & Null Handling
- Use ternary operators for null checks: `(value != null) ? value : defaultValue`
- Avoid raw types, use generics: `Map<String, Object>` instead of `Map`
- Validate input in controllers before processing
- Use Optional for methods that may not return a value

### Comments Style
- Minimal comments - only explain "why", not "what"
- Add comments only at method beginnings or in complex logic sections
- Avoid line-by-line comments
- NEVER add examples in comments
- Avoid "this was already correct" or similar prompt-related comments

## TypeScript/Frontend Code Style Guidelines

### Component Architecture
- Use functional components with hooks only (no class components)
- Component files use PascalCase: `UserMenu.tsx`, `DisplayGraph.tsx`
- Utility files use lowercase: `hash.ts`, `useApi.ts`
- Organize in directories: `components/`, `hooks/`, `context/`, `utils/`, `types/`

### Naming Conventions
- Components: PascalCase (e.g., `ThemeToggle`, `PlaybackControls`)
- Custom hooks: `use` prefix + PascalCase (e.g., `useApi`, `useGraph`, `useWebSocketConnection`)
- Variables/functions: camelCase (e.g., `clientId`, `subscribe`)
- Constants: UPPER_SNAKE_CASE (e.g., `CLIENT_ID`)
- Types/Interfaces: PascalCase (e.g., `SocketEnvelope`, `PositionUpdate`)

### State Management
- Use Context API for global state (auth, simulation, theme)
- Use `useState` for component-local state
- Use `useMemo` for expensive computations
- Use `useCallback` for event handlers passed to children
- Avoid prop drilling - create context when needed

### Hooks Pattern
- Custom hooks use `use` prefix
- Encapsulate complex logic and side effects
- Return consistent interfaces (e.g., `{ connected, subscribe }` from `useWebSocketConnection`)
- Use dependency arrays correctly in `useEffect` and `useCallback`

### TypeScript & Types
- Strong typing everywhere - avoid `any`
- Use interfaces for objects, enums for constants
- Import types from auto-generated `api-client` package
- Type WebSocket messages with interfaces in `types/WebsocketTypes.ts`
- Use generic handlers: `type GenericHandler = (data: any) => void`

### Imports
Organize by type:
1. React imports (`import React, { useState, useEffect } from 'react'`)
2. Third-party libraries (`import { Client } from '@stomp/stompjs'`)
3. Internal components/hooks (`import { useApi } from '../hooks/useApi'`)
4. Types (`import type { SocketEnvelope } from '../types/WebsocketTypes'`)

### Styling
- Use Tailwind CSS utility classes
- Conditional class names with template literals
- Dark mode support: `dark:bg-gray-700 dark:text-white`
- Responsive design: `md:flex`, `lg:w-64`
- Animation/transitions: `transition-all duration-200`
- Inline styles only when necessary (e.g., dynamic values)

### WebSocket Integration
- Use `@stomp/stompjs` with `useWebSocketConnection` hook
- Subscribe pattern: `subscribe(topic, handler)` returns unsubscribe function
- Filter echoes: check `senderId !== clientId` before processing
- Inject timestamp from envelope into payload
- Manage subscriptions in a Map to support multiple handlers per topic

### Error Handling
- Use `react-hot-toast` for user notifications
- Try-catch around JSON parsing in WebSocket handlers
- Graceful degradation when APIs are unavailable
- Log errors to console with context

### Comments Style
- Minimal comments focused on complex logic
- No line-by-line explanations
- No examples in comments
- Describe intent at function level only

## Architecture Patterns

### Event Sourcing (Backend)
- Source of truth is immutable event log in ClickHouse
- Events are published to RabbitMQ for async processing
- Current state derived from events in OrientDB and Redis
- Never mutate event data - append-only pattern

### Polyglot Persistence
- OrientDB: Graph database for topology (nodes, edges)
- Redis: Real-time state (item positions, active items)
- ClickHouse: Event store (immutable log of all events)
- Context-aware sessions support live vs simulation views

### Asynchronous Processing
- Controllers publish events and return `202 Accepted`
- Event listeners consume from RabbitMQ
- `EventProcessor` maintains ordering guarantees
- Use `CompletableFuture` for async operations

### Simulation Support
- `DatabaseContextHolder` provides context for multi-tenancy
- Redis uses namespacing: `sim:<simulationId>:<key>` to isolate data
- In-memory OrientDB clones created for simulation workloads
- "Golden template" pattern for fast schema initialization

### API Design
- RESTful endpoints in `*Controller` classes
- Use `ResponseEntity` for proper HTTP status codes
- DTOs separate from domain models in `models/response/` and `models/input/`
- Auto-generated OpenAPI docs available at `/api-docs`

### Important Patterns
- **Service Layer**: Business logic, dependency injection via constructor
- **Repository Layer**: Data access, handle Redis/OrientDB/ClickHouse
- **Controller Layer**: REST endpoints, validation, async event publishing
- **DTO Mapping**: Convert domain objects to response DTOs before sending to client
- **Path Caching**: Cache Dijkstra results in Redis with TTL for performance

## Development Notes

- Avoid examples in comments - be concise and direct
- Always provide full code files - don't omit lines for brevity
- Run `pnpm lint` and `mvn test` after making changes
- Check GEMINI.md and TODO.md for project context
- Backend uses Java 21, Spring Boot 3.3.5
- Frontend uses React 18, Vite 5, TypeScript 5.5
- Database connections require environment variables from `.env` file
