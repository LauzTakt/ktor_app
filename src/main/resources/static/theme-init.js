// Loaded as a classic, blocking script in every page's <head>: applies the saved theme (else the system preference)
// before the first paint, so a dark page never flashes light. The toggle itself lives in common.js (`setTheme`).
document.documentElement.dataset.theme =
  localStorage.getItem("twin-theme") || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
