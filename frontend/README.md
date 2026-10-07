# To Run

Use Node.js 22.13+ or 24+ and pnpm 10.

```sh
pnpm install --frozen-lockfile
pnpm dev
```

Type checking uses TypeScript 7 via the `@typescript/native` alias. The
`typescript` alias supplies the TypeScript 6 API required by typescript-eslint,
following [Microsoft's side-by-side setup](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/#running-side-by-side-with-typescript-6-0).

## to login into trigger dev

pnpm --dir ../assistant run login-link
