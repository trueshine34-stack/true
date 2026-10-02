/**
 * The build's own name, shown at the foot of the settings.
 *
 * Two builds that look alike are impossible to tell apart when one of them
 * failed to install, and "is this the one with the fix" is a question the
 * screen should answer rather than the memory.
 *
 * Read from the gradle file at build time rather than typed here. It was typed
 * here once, and it drifted ten builds behind the APK it was supposed to name —
 * so the screen confidently gave the wrong answer to the one question it exists
 * to answer. A number nobody has to remember to change cannot do that.
 */
declare const __APP_VERSION__: string;

export const APP_VERSION =
  typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev';
