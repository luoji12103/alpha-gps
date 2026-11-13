import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";

// GitHub Pages project site base path.
export default defineConfig({
  site: "https://luoji12103.github.io",
  base: "/alpha-gps/",
  trailingSlash: "always",
  integrations: [sitemap()]
});


