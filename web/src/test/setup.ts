import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";

// Route tests load the whole app with its lazy route chunks; on a busy machine the first
// screen can take longer than the default second.
configure({ asyncUtilTimeout: 5000 });

// jsdom has no matchMedia (sidebar, theme) nor ResizeObserver (charts, Radix).
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}
if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}
// Radix Select and cmdk scroll items into view and check pointer capture.
Element.prototype.scrollIntoView ??= () => {};
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.releasePointerCapture ??= () => {};

afterEach(() => {
  cleanup();
  document.cookie.split(";").forEach((cookie) => {
    const name = cookie.split("=")[0].trim();
    // biome-ignore lint/suspicious/noDocumentCookie: jsdom has no Cookie Store API
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  });
});
