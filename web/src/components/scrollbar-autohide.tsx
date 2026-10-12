import { useEffect } from "react";

/** Marks the element being scrolled with .is-scrolling for 700 ms (see index.css, I18). */
export function ScrollbarAutohide() {
  useEffect(() => {
    const timers = new WeakMap<Element, number>();
    const onScroll = (event: Event) => {
      const target = event.target instanceof Element ? event.target : document.scrollingElement;
      if (!target) return;
      target.classList.add("is-scrolling");
      window.clearTimeout(timers.get(target));
      timers.set(
        target,
        window.setTimeout(() => target.classList.remove("is-scrolling"), 700),
      );
    };
    document.addEventListener("scroll", onScroll, { capture: true, passive: true });
    return () => document.removeEventListener("scroll", onScroll, { capture: true });
  }, []);
  return null;
}
