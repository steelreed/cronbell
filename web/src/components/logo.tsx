/** The Cronbell product icon (brand/cronbell.svg): the only place amber is decorative. */
export function CronbellMark({ size = 18 }: { size?: number }) {
  return (
    <svg
      viewBox="0 0 64 64"
      width={size}
      height={size}
      aria-hidden
      className="shrink-0 rounded-[4px]"
    >
      <rect width="64" height="64" fill="#0B1D33" />
      <rect x="28.5" y="9" width="7" height="7" rx="3.5" fill="#F2F7FC" />
      <path d="M17 41V30.5C17 21.9 23.7 15 32 15s15 6.9 15 15.5V41Z" fill="#F2F7FC" />
      <rect x="11" y="38" width="42" height="7" rx="3.5" fill="#F2F7FC" />
      <circle cx="32" cy="51.5" r="5" fill="#F5A524" />
    </svg>
  );
}
