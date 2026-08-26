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

/**
 * A fold whose heading is the switch that turns the thing on.
 *
 * A section containing nothing but a toggle is a section for nothing: the
 * toggle may as well be the heading. Tapping the switch turns it on or off,
 * tapping the rest of the row opens what it is made of.
 */
export function SwitchFold({
  title,
  note,
  on,
  onToggle,
  children,
}: {
  title: string;
  note?: string;
  on: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`card fold${open ? ' open' : ''}`}>
      <div className="foldhead switchhead">
        <button
          className={`switch ${on ? 'on' : ''}`}
          onClick={onToggle}
          aria-pressed={on}
          aria-label={title}
        />
        <button className="foldhead-rest" onClick={() => setOpen((v) => !v)}>
          <span>{title}</span>
          {note && <span className="muted foldnote">{note}</span>}
          <span className="foldarrow" aria-hidden>
            {open ? '−' : '+'}
          </span>
        </button>
      </div>
      {open && <div className="foldbody">{children}</div>}
    </div>
  );
}
