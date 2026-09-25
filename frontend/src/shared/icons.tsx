import type { SVGProps } from 'react';

type IconProps = SVGProps<SVGSVGElement>;

function icone(paths: React.ReactNode, props: IconProps) {
  return (
    <svg
      width={props.width ?? 18}
      height={props.height ?? 18}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...props}
    >
      {paths}
    </svg>
  );
}

export function IconSearch(props: IconProps = {}) {
  return icone(
    <>
      <circle cx="11" cy="11" r="7" />
      <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </>,
    props,
  );
}

export function IconCheck(props: IconProps = {}) {
  return icone(<polyline points="20 6 9 17 4 12" />, { strokeWidth: 2.4, ...props });
}

export function IconDocument(props: IconProps = {}) {
  return icone(
    <>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
    </>,
    props,
  );
}

export function IconTray(props: IconProps = {}) {
  return icone(
    <>
      <path d="M4 12h4l2 3h4l2-3h4" />
      <path d="M4 12l1.5-7A2 2 0 0 1 7.45 3.5h9.1a2 2 0 0 1 1.95 1.5L20 12" />
      <path d="M4 12v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6" />
    </>,
    props,
  );
}

export function IconMapPin(props: IconProps = {}) {
  return icone(
    <>
      <path d="M21 10c0 6-9 12-9 12s-9-6-9-12a9 9 0 0 1 18 0z" />
      <circle cx="12" cy="10" r="3" />
    </>,
    props,
  );
}

export function IconUsers(props: IconProps = {}) {
  return icone(
    <>
      <path d="M17 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2" />
      <circle cx="10" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </>,
    props,
  );
}

export function IconChart(props: IconProps = {}) {
  return icone(
    <>
      <line x1="12" y1="20" x2="12" y2="10" />
      <line x1="18" y1="20" x2="18" y2="4" />
      <line x1="6" y1="20" x2="6" y2="16" />
    </>,
    props,
  );
}

export function IconCloudSync(props: IconProps = {}) {
  return icone(
    <>
      <path d="M17.5 19H9a5.5 5.5 0 1 1 .34-10.99A7 7 0 0 1 22 12.5a4.5 4.5 0 0 1-1.5 8.5" />
      <path d="M12 12v5" />
      <polyline points="9.5 15 12 17.5 14.5 15" />
    </>,
    props,
  );
}

export function IconLogout(props: IconProps = {}) {
  return icone(
    <>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <polyline points="16 17 21 12 16 7" />
      <line x1="21" y1="12" x2="9" y2="12" />
    </>,
    props,
  );
}

export function IconAlertTriangle(props: IconProps = {}) {
  return icone(
    <>
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </>,
    props,
  );
}

export function IconCopy(props: IconProps = {}) {
  return icone(
    <>
      <rect x="9" y="9" width="13" height="13" rx="2" />
      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </>,
    props,
  );
}

export function IconChevronLeft(props: IconProps = {}) {
  return icone(<polyline points="15 18 9 12 15 6" />, { strokeWidth: 2.2, ...props });
}

export function IconDownload(props: IconProps = {}) {
  return icone(
    <>
      <path d="M12 3v12" />
      <polyline points="7 10 12 15 17 10" />
      <path d="M5 21h14" />
    </>,
    props,
  );
}

export function IconKey(props: IconProps = {}) {
  return icone(
    <>
      <rect x="3" y="11" width="18" height="10" rx="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </>,
    props,
  );
}
