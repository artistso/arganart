Build the private, on-device StyleFingerprintProvider. This is the app's answer to
"everything looks the same": a per-artist visual DNA that biases every generation
away from the generic mean and toward the individual.

FINGERPRINTING
- Ingest from: the user's own strokes in this app, an imported folder of images,
  and camera-roll selections the user explicitly picks. Nothing is ingested
  implicitly.
- Compute a compact feature vector per source, then aggregate into a
  StyleFingerprint containing at minimum:
  - Line-weight distribution (histogram + mean/variance)
  - Color temperature and saturation bias
  - Contrast and value-range preference
  - Texture energy / edge-density statistics
  - Composition habits (subject placement bias, symmetry preference,
    negative-space ratio)
  - Stroke velocity and curvature distributions (from in-app strokes)
- All computation runs on-device. Document the algorithms; no black boxes.

STORAGE & PRIVACY
- Fingerprint stored encrypted at rest (Jetpack Security / Android Keystore).
- Explicit, plain-language privacy screen: "your sketches never leave this tablet."
- A single "Delete my fingerprint" action that is immediate and complete.
- No analytics on fingerprint contents. Ever.

INTEGRATION
- ModelBackend requests carry a StyleFingerprint reference. Implement the
  conditioning path appropriate to the chosen model (LoRA-free approaches
  preferred: prompt/embedding conditioning, CFG offset, or palette transfer —
  choose and justify).
- Add a "Show my fingerprint" screen: a readable radar chart plus example
  strokes, so the user can see and trust what the app thinks their style is.

DEFINITION OF DONE
- Importing 20 images produces a visibly distinct fingerprint from a second
  artist's 20 images.
- The same request with two different fingerprints produces measurably different
  outputs (document the metric).
- Fingerprint persists across app restarts, decrypts correctly, and deletes
  cleanly.