import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          // テスト専用の値。本番の値は wrangler.jsonc と `wrangler secret put` で設定する。
          bindings: {
            TEST_MIGRATIONS: migrations,
            GOOGLE_WEB_CLIENT_ID: "test-client.apps.googleusercontent.com",
            ANTHROPIC_API_KEY: "test-api-key",
            SESSION_SECRET: "test-session-secret-0123456789abcdef0123456789",
            DAILY_LIMIT: "3",
          },
        },
      }),
    ],
    test: {
      include: ["test/**/*.test.ts"],
    },
  };
});
