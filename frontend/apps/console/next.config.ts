import type { NextConfig } from "next";

// Static export served by the reverse proxy under one origin (ADR-0012). basePath is the app's URL prefix.
const basePath = "/console";

const config: NextConfig = {
  output: "export",
  basePath,
  trailingSlash: true,
  env: { NEXT_PUBLIC_BASE_PATH: basePath },
  transpilePackages: ["@qms/api-client", "@qms/i18n", "@qms/ui", "@qms/realtime-client"],
};

export default config;
