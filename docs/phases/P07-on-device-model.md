Wire a real on-device generative model behind the ModelBackend interface. This is
infrastructure, not product design: get generation working reliably and
cancellably, then we'll tune the experience in later phases.

RUNTIME
- Use ONNX Runtime Mobile (or NCNN/MNN if you can justify it) with NNAPI and GPU
  delegates where available, CPU fallback always present.
- Ship with ONE small quantized model (e.g. a distilled few-step diffusion model,
  aggressively quantized) small enough to bundle or fetch on first run.
  The model choice is yours — document size, latency, and quality in DECISIONS.md.
- Model download (if used) is explicit, user-visible, resumable, and optional;
  the app must remain fully usable with all AI disabled.

BACKEND REQUIREMENTS
- Streaming results: emit Partial frames so the canvas can show progressive
  refinement rather than a spinner.
- Cancellation: cancelling returns within one frame; no orphaned GPU work.
- Frame/thermal guards: monitor thermal status and battery; degrade step count
  or resolution automatically and surface this to the user honestly.
- Determinism: same request + same seed = same output, bit-identical on the same
  device. Store the seed and model version with every generated result.
- Memory: hard cap configured per device RAM class; evict aggressively.
- All generation runs off the main thread; the ink layer must never stutter while
  generation is running (verify with JankStats).

INTEGRATION
- Add one new generative brush: "Imagine brush," which takes the current
  GenerationRequest (from P3) and streams a generated patch into the Frame
  region, composited under the user's ink.
- Result arrives as a new canvas layer the user can accept, discard, or keep
  painting over. Discard must be one gesture.

DEFINITION OF DONE
- Generation runs on a real tablet without dropping ink frames.
- Cancel is instant and leak-free.
- Thermal throttling degrades gracefully with a visible but non-intrusive notice.
- App remains fully functional with the model file deleted.