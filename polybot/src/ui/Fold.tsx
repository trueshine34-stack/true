import { useState, type ReactNode } from 'react';

/**
 * A section that stays shut until it is wanted.
 *
 * The settings had grown to several screens of fields, which is several
 * screens to scroll past to change the one thing you came for. Folded, the
 * whole page is a list of headings you can see at once — and the heading
 * carries the setting's current value, so most of the time it does not have to
 * be opened at all.
 */
export function Fold({
  title,
  note,
  children,
}: {
  title: string;
  note?: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`card fold${open ? ' open' : ''}`}>
      <button className="foldhead" onClick={() => setOpen((v) => !v)}>
        <span>{title}</span>
        {note && <span className="muted foldnote">{note}</span>}
        <span className="foldarrow" aria-hidden>
          {open ? '−' : '+'}
        </span>
      </button>
      {open && <div className="foldbody">{children}</div>}
    </div>
  );
}
