export const CLOUDFLARE_WORKER_BASE = "https://prophecy-verse-video-api.newprophecy4.workers.dev";

/**
 * Clean path/folder segment to avoid invalid URL characters or directory traversal
 */
export function sanitizePathSegment(segment: string): string {
  return segment
    .trim()
    .replace(/[\\/:*?"<>|]/g, "_")
    .replace(/\.\./g, "_")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Generate structured object key for Cloudflare R2
 * Example: "Jujutsu Kaisen/Season 1/Episode 01.mp4"
 */
export function generateR2ObjectKey(
  animeTitle: string,
  seasonNum: number,
  episodeNum: number,
  originalFileName: string
): string {
  const cleanTitle = sanitizePathSegment(animeTitle || "Anime") || "Anime";
  const extMatch = originalFileName.match(/\.([a-zA-Z0-9]+)$/);
  const ext = extMatch ? extMatch[1].toLowerCase() : "mp4";
  const paddedEpNum = episodeNum < 10 ? `0${episodeNum}` : `${episodeNum}`;

  return `${cleanTitle}/Season ${seasonNum}/Episode ${paddedEpNum}.${ext}`;
}

/**
 * Returns public video streaming URL for a Cloudflare R2 object key
 */
export function buildCloudflareVideoUrl(objectKey: string): string {
  if (!objectKey) return "";
  const encodedParts = objectKey.split("/").map(part => encodeURIComponent(part)).join("/");
  return `${CLOUDFLARE_WORKER_BASE}/video/${encodedParts}`;
}

let cachedUploadConfig: { workerBaseUrl: string; adminToken: string } | null = null;
let uploadConfigPromise: Promise<{ workerBaseUrl: string; adminToken: string }> | null = null;

export async function getUploadConfig(): Promise<{ workerBaseUrl: string; adminToken: string }> {
  if (cachedUploadConfig) return cachedUploadConfig;
  if (!uploadConfigPromise) {
    uploadConfigPromise = (async () => {
      try {
        const res = await fetch("/api/cloudflare/upload-config");
        if (res.ok) {
          const data = await res.json();
          if (data.success && data.adminToken) {
            cachedUploadConfig = {
              workerBaseUrl: data.workerBaseUrl || CLOUDFLARE_WORKER_BASE,
              adminToken: data.adminToken
            };
            return cachedUploadConfig;
          }
        }
      } catch (e) {
        console.warn("[Cloudflare Service] upload-config fetch error:", e);
      }
      cachedUploadConfig = {
        workerBaseUrl: CLOUDFLARE_WORKER_BASE,
        adminToken: ""
      };
      return cachedUploadConfig;
    })();
  }
  return uploadConfigPromise;
}

export interface CloudflareProgressPayload {
  uploadedBytes: number;
  totalBytes: number;
  percentage: number;
  speed: string;
  remainingTime: string;
  currentPart?: number;
  totalParts?: number;
  uploadId?: string | null;
  objectKey?: string;
  activeChunkSize?: number;
  etag?: string;
}

export interface CloudflareUploadController {
  abort: () => void;
}

export type CloudflareErrorClassification =
  | "CORS_ERROR"
  | "NETWORK_ERROR"
  | "HTTP_400"
  | "HTTP_403"
  | "HTTP_404"
  | "HTTP_408"
  | "HTTP_429"
  | "HTTP_5XX"
  | "PRESIGNED_URL_ERROR"
  | "PRESIGNED_URL_EXPIRED"
  | "UNKNOWN_ERROR";

export interface CloudflareUploadErrorInfo {
  stage?: string;
  httpStatus?: number | string;
  httpMethod?: string;
  endpoint?: string;
  responseBody?: string;
  errorCode?: string;
  errorClassification?: CloudflareErrorClassification;
  uploadId?: string | null;
  objectKey?: string;
  currentPart?: number;
  totalParts?: number;
  attempt?: number;
  chunkSize?: number;
  etag?: string;
}

export function sanitizeSecrets(text: string): string {
  if (!text) return "";
  return text
    .replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, "Bearer [REDACTED_TOKEN]")
    .replace(/x-admin-token:\s*[A-Za-z0-9_.-]+/gi, "x-admin-token: [REDACTED_TOKEN]")
    .replace(/adminToken":\s*"[^"]+"/gi, 'adminToken": "[REDACTED]"')
    .replace(/accessKeyId":\s*"[^"]+"/gi, 'accessKeyId": "[REDACTED]"')
    .replace(/secretAccessKey":\s*"[^"]+"/gi, 'secretAccessKey": "[REDACTED]"');
}

/**
 * Direct Cloudflare Worker Multipart API Helper Functions
 * These make direct calls from the browser to the Cloudflare Worker URL.
 */

export async function initMultipartUpload(params: {
  filename: string;
  contentType: string;
  size: number;
  folder: string;
  key?: string;
}): Promise<{ success: boolean; uploadId?: string; key?: string; error?: string; raw?: any }> {
  try {
    // Primary: Secure server backend proxy (keeps admin tokens completely secret)
    let targetUrl = "/api/r2/multipart/init";
    let headers: Record<string, string> = { "Content-Type": "application/json" };

    let res = await fetch(targetUrl, {
      method: "POST",
      headers,
      body: JSON.stringify(params)
    }).catch(() => null);

    // Fallback to direct worker URL if server endpoint isn't available
    if (!res || !res.ok) {
      targetUrl = `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/init`;
      res = await fetch(targetUrl, {
        method: "POST",
        headers,
        body: JSON.stringify(params)
      }).catch(() => null);
    }

    if (!res) {
      return { success: false, error: "Failed to connect to Multipart Init endpoint" };
    }

    const data = await res.json().catch(() => ({}));
    if (res.ok && data.success && data.uploadId) {
      return {
        success: true,
        uploadId: data.uploadId,
        key: data.key || params.key,
        raw: data
      };
    }
    return {
      success: false,
      error: data.error || data.message || `HTTP ${res.status} during multipart init`,
      raw: data
    };
  } catch (err: any) {
    return {
      success: false,
      error: err.message || "Network error during multipart init"
    };
  }
}

export async function getPartPresignedUrl(params: {
  key: string;
  uploadId: string;
  partNumber: number;
}): Promise<{ success: boolean; uploadUrl?: string; partNumber?: number; error?: string; raw?: any }> {
  try {
    let targetUrl = "/api/r2/multipart/part-url";
    let headers: Record<string, string> = { "Content-Type": "application/json" };

    let res = await fetch(targetUrl, {
      method: "POST",
      headers,
      body: JSON.stringify(params)
    }).catch(() => null);

    if (!res || !res.ok) {
      targetUrl = `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/part-url`;
      res = await fetch(targetUrl, {
        method: "POST",
        headers,
        body: JSON.stringify(params)
      }).catch(() => null);
    }

    if (!res) {
      return { success: false, error: "Failed to connect to Part URL endpoint" };
    }

    const data = await res.json().catch(() => ({}));
    if (res.ok && data.success && data.uploadUrl) {
      return {
        success: true,
        uploadUrl: data.uploadUrl,
        partNumber: data.partNumber || params.partNumber,
        raw: data
      };
    }
    return {
      success: false,
      error: data.error || data.message || `HTTP ${res.status} fetching part URL`,
      raw: data
    };
  } catch (err: any) {
    return {
      success: false,
      error: err.message || "Network error fetching part URL"
    };
  }
}

export async function completeMultipartUpload(params: {
  key: string;
  uploadId: string;
  parts: Array<{ partNumber: number; etag: string }>;
}): Promise<{ success: boolean; error?: string; raw?: any }> {
  try {
    let targetUrl = "/api/r2/multipart/complete";
    let headers: Record<string, string> = { "Content-Type": "application/json" };

    let res = await fetch(targetUrl, {
      method: "POST",
      headers,
      body: JSON.stringify(params)
    }).catch(() => null);

    if (!res || !res.ok) {
      targetUrl = `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/complete`;
      res = await fetch(targetUrl, {
        method: "POST",
        headers,
        body: JSON.stringify(params)
      }).catch(() => null);
    }

    if (!res) {
      return { success: false, error: "Failed to connect to Complete endpoint" };
    }

    const data = await res.json().catch(() => ({}));
    if (res.ok && data.success) {
      return { success: true, raw: data };
    }
    return {
      success: false,
      error: data.error || data.message || `HTTP ${res.status} completing multipart upload`,
      raw: data
    };
  } catch (err: any) {
    return {
      success: false,
      error: err.message || "Network error completing multipart upload"
    };
  }
}

export async function abortMultipartUpload(params: {
  key: string;
  uploadId: string;
}): Promise<{ success: boolean; error?: string }> {
  try {
    let targetUrl = "/api/r2/multipart/abort";
    let headers: Record<string, string> = { "Content-Type": "application/json" };

    let res = await fetch(targetUrl, {
      method: "DELETE",
      headers,
      body: JSON.stringify(params)
    }).catch(() => null);

    if (!res || !res.ok) {
      targetUrl = `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/abort`;
      res = await fetch(targetUrl, {
        method: "DELETE",
        headers,
        body: JSON.stringify(params)
      }).catch(() => null);
    }

    if (!res) return { success: false, error: "Abort request failed" };

    const data = await res.json().catch(() => ({}));
    return { success: res.ok && data.success, error: data.error };
  } catch (err: any) {
    return { success: false, error: err.message };
  }
}

export async function checkR2ObjectExists(key: string): Promise<{ success: boolean; exists: boolean; key?: string }> {
  try {
    let targetUrl = `/api/r2/check?key=${encodeURIComponent(key)}`;
    let res = await fetch(targetUrl, { method: "GET" }).catch(() => null);

    if (!res || !res.ok) {
      targetUrl = `${CLOUDFLARE_WORKER_BASE}/api/r2/check?key=${encodeURIComponent(key)}`;
      res = await fetch(targetUrl, { method: "GET" }).catch(() => null);
    }

    if (res && res.ok) {
      const data = await res.json();
      return {
        success: !!data.success,
        exists: !!data.exists,
        key: data.key || key
      };
    }
    return { success: false, exists: false, key };
  } catch (err) {
    return { success: false, exists: false, key };
  }
}

/**
 * Helper to classify HTTP and network error responses
 */
function classifyErrorStatus(status: number): CloudflareErrorClassification {
  if (status === 0) return "CORS_ERROR";
  if (status === 400) return "HTTP_400";
  if (status === 403) return "HTTP_403";
  if (status === 404) return "HTTP_404";
  if (status === 408) return "HTTP_408";
  if (status === 429) return "HTTP_429";
  if (status >= 500 && status < 600) return "HTTP_5XX";
  return "UNKNOWN_ERROR";
}

/**
 * Uploads a video file directly to Cloudflare R2 using the Worker's Multipart API
 */
export function uploadToCloudflareR2(
  file: File,
  objectKey: string,
  options: {
    onProgress?: (progress: CloudflareProgressPayload) => void;
    onSuccess?: (videoUrl: string, key: string, uploadId?: string) => void;
    onError?: (errorMsg: string, details?: string, errorInfo?: CloudflareUploadErrorInfo) => void;
  }
): CloudflareUploadController {
  let isAborted = false;
  let activeXhr: XMLHttpRequest | null = null;
  let activeUploadId: string | null = null;
  let activeKey: string = objectKey;

  const abort = async () => {
    isAborted = true;
    if (activeXhr) {
      try { activeXhr.abort(); } catch (_) {}
    }
    if (activeUploadId && activeKey) {
      try {
        await abortMultipartUpload({ key: activeKey, uploadId: activeUploadId });
      } catch (_) {}
    }
  };

  const startUpload = async () => {
    // 1. Derive folder and filename from objectKey while preserving logical structure
    let folder = "";
    let filename = objectKey;
    if (objectKey.includes("/")) {
      const lastSlash = objectKey.lastIndexOf("/");
      folder = objectKey.substring(0, lastSlash);
      filename = objectKey.substring(lastSlash + 1);
    }

    // 2. Initialize multipart upload directly with Cloudflare Worker
    const initResult = await initMultipartUpload({
      filename,
      contentType: file.type || "video/mp4",
      size: file.size,
      folder,
      key: objectKey
    });

    if (isAborted) return;

    if (!initResult.success || !initResult.uploadId) {
      if (options.onError) {
        options.onError(
          "Failed to initialize R2 multipart upload.",
          initResult.error || "Worker returned unsuccessful response during init.",
          {
            stage: "initializing",
            httpStatus: 500,
            httpMethod: "POST",
            endpoint: `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/init`,
            responseBody: sanitizeSecrets(JSON.stringify(initResult.raw || {})),
            errorCode: "MULTIPART_INIT_FAILED",
            errorClassification: "HTTP_5XX",
            uploadId: null,
            objectKey
          }
        );
      }
      return;
    }

    activeUploadId = initResult.uploadId;
    activeKey = initResult.key || objectKey;

    // 3. Split file into 64 MiB multipart chunks (Default chunk size: 64 MiB)
    const CHUNK_SIZE = 64 * 1024 * 1024;
    const totalParts = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));
    const parts: Array<{ partNumber: number; etag: string }> = [];

    const startTime = Date.now();
    let loadedBeforeCurrentPart = 0;

    // 4. Upload each part directly (with CORS-safe fallback to server streaming proxy)
    for (let partNum = 1; partNum <= totalParts; partNum++) {
      if (isAborted) return;

      const start = (partNum - 1) * CHUNK_SIZE;
      const end = Math.min(file.size, start + CHUNK_SIZE);
      const chunk = file.slice(start, end);

      const partUrlResult = await getPartPresignedUrl({
        key: activeKey,
        uploadId: activeUploadId,
        partNumber: partNum
      });

      if (isAborted) return;

      if (!partUrlResult.success || !partUrlResult.uploadUrl) {
        if (activeUploadId) {
          try { await abortMultipartUpload({ key: activeKey, uploadId: activeUploadId }); } catch (_) {}
        }
        if (options.onError) {
          options.onError(
            `Failed to get upload URL for part ${partNum} of ${totalParts}.`,
            partUrlResult.error || "Missing uploadUrl in response.",
            {
              stage: "uploading",
              httpStatus: 500,
              httpMethod: "POST",
              endpoint: `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/part-url`,
              responseBody: sanitizeSecrets(JSON.stringify(partUrlResult.raw || {})),
              errorCode: `PRESIGNED_URL_ERROR`,
              errorClassification: "PRESIGNED_URL_ERROR",
              uploadId: activeUploadId,
              objectKey: activeKey,
              currentPart: partNum,
              totalParts,
              chunkSize: chunk.size
            }
          );
        }
        return;
      }

      const uploadUrl = partUrlResult.uploadUrl;
      let partSuccess = false;
      let etag = "";
      let lastPartErr = "";
      let lastHttpStatus: number | string = 0;
      let lastClassification: CloudflareErrorClassification = "UNKNOWN_ERROR";
      let lastAttempt = 1;

      // Retry up to 3 attempts with exponential backoff
      for (let attempt = 1; attempt <= 3; attempt++) {
        if (isAborted) return;
        lastAttempt = attempt;

        if (attempt > 1) {
          const backoffMs = Math.pow(2, attempt - 2) * 1000; // 1s, 2s
          console.log(`[R2 Multipart Upload] Retrying Part ${partNum} (Attempt ${attempt}/3) after ${backoffMs}ms...`);
          await new Promise(r => setTimeout(r, backoffMs));
          if (isAborted) return;
        }

        try {
          const uploadSingleTarget = (targetUrl: string, headers: Record<string, string>) => {
            return new Promise<{
              ok: boolean;
              status: number;
              etag: string;
              responseText: string;
              isCorsOrNetwork: boolean;
            }>((resolve) => {
              const xhr = new XMLHttpRequest();
              activeXhr = xhr;

              xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                  const totalLoaded = loadedBeforeCurrentPart + e.loaded;
                  const now = Date.now();
                  const elapsedSec = (now - startTime) / 1000;
                  const bytesPerSec = elapsedSec > 0 ? totalLoaded / elapsedSec : 0;

                  let speedStr = "Calculating...";
                  if (bytesPerSec > 1024 * 1024) {
                    speedStr = `${(bytesPerSec / (1024 * 1024)).toFixed(2)} MB/s`;
                  } else if (bytesPerSec > 1024) {
                    speedStr = `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
                  } else {
                    speedStr = `${Math.round(bytesPerSec)} B/s`;
                  }

                  const remainingBytes = file.size - totalLoaded;
                  let remainingStr = "Calculating...";
                  if (bytesPerSec > 0) {
                    const remSec = Math.ceil(remainingBytes / bytesPerSec);
                    if (remSec >= 60) {
                      remainingStr = `${Math.floor(remSec / 60)}m ${remSec % 60}s remaining`;
                    } else {
                      remainingStr = `${remSec}s remaining`;
                    }
                  }

                  const percentage = Math.min(99, Math.round((totalLoaded / file.size) * 100));

                  if (options.onProgress) {
                    options.onProgress({
                      uploadedBytes: totalLoaded,
                      totalBytes: file.size,
                      percentage,
                      speed: speedStr,
                      remainingTime: remainingStr,
                      currentPart: partNum,
                      totalParts,
                      uploadId: activeUploadId,
                      objectKey: activeKey,
                      activeChunkSize: chunk.size
                    });
                  }
                }
              };

              xhr.onload = () => {
                let rawEtag = xhr.getResponseHeader("ETag") || xhr.getResponseHeader("etag") || "";
                let cleanEtag = rawEtag.replace(/^"|"$/g, "");
                
                // If response is JSON from our backend proxy
                if (!cleanEtag && xhr.responseText) {
                  try {
                    const parsed = JSON.parse(xhr.responseText);
                    if (parsed.etag) cleanEtag = parsed.etag;
                  } catch (_) {}
                }

                if (xhr.status >= 200 && xhr.status < 300) {
                  resolve({
                    ok: true,
                    status: xhr.status,
                    etag: cleanEtag,
                    responseText: xhr.responseText,
                    isCorsOrNetwork: false
                  });
                } else {
                  resolve({
                    ok: false,
                    status: xhr.status,
                    etag: "",
                    responseText: xhr.responseText || `HTTP ${xhr.status} ${xhr.statusText}`,
                    isCorsOrNetwork: false
                  });
                }
              };

              xhr.onerror = () => {
                resolve({
                  ok: false,
                  status: 0,
                  etag: "",
                  responseText: "Browser CORS or Network Error uploading part directly to Cloudflare R2 presigned URL.",
                  isCorsOrNetwork: true
                });
              };

              xhr.onabort = () => {
                resolve({
                  ok: false,
                  status: 0,
                  etag: "",
                  responseText: "Part upload cancelled",
                  isCorsOrNetwork: false
                });
              };

              xhr.open("PUT", targetUrl, true);
              for (const [k, v] of Object.entries(headers)) {
                xhr.setRequestHeader(k, v);
              }
              xhr.send(chunk);
            });
          };

          // Step A: Attempt direct PUT to presigned R2 storage URL
          let uploadRes = await uploadSingleTarget(uploadUrl, {});

          // Step B: If direct upload fails due to CORS or Network or missing ETag headers, fallback to server streaming proxy
          if (uploadRes.isCorsOrNetwork || (!uploadRes.ok && uploadRes.status === 0)) {
            console.log(`[R2 Upload] Direct PUT hit CORS / Network restriction for part ${partNum}. Using secure proxy...`);
            uploadRes = await uploadSingleTarget("/api/r2/multipart/upload-part", {
              "x-target-url": uploadUrl,
              "Content-Type": file.type || "video/mp4"
            });
          }

          activeXhr = null;

          if (uploadRes.ok && uploadRes.etag) {
            partSuccess = true;
            etag = uploadRes.etag;
            break;
          } else {
            lastHttpStatus = uploadRes.status === 0 ? "CORS / Network Error" : uploadRes.status;
            lastClassification = uploadRes.isCorsOrNetwork ? "CORS_ERROR" : classifyErrorStatus(uploadRes.status);
            lastPartErr = uploadRes.responseText || `Part ${partNum} failed with status ${uploadRes.status}`;
            console.warn(`[R2 Multipart Upload] Part ${partNum} attempt ${attempt} failed [${lastClassification}]:`, lastPartErr);
          }
        } catch (err: any) {
          activeXhr = null;
          lastHttpStatus = 0;
          lastClassification = "NETWORK_ERROR";
          lastPartErr = err?.message || String(err);
        }
      }

      if (!partSuccess) {
        if (activeUploadId) {
          try { await abortMultipartUpload({ key: activeKey, uploadId: activeUploadId }); } catch (_) {}
        }
        if (options.onError) {
          options.onError(
            `Failed to upload part ${partNum} of ${totalParts} after 3 attempts (${lastClassification}).`,
            lastPartErr,
            {
              stage: "uploading",
              httpStatus: lastHttpStatus,
              httpMethod: "PUT",
              endpoint: uploadUrl.split("?")[0],
              responseBody: sanitizeSecrets(lastPartErr),
              errorCode: lastClassification,
              errorClassification: lastClassification,
              uploadId: activeUploadId,
              objectKey: activeKey,
              currentPart: partNum,
              totalParts,
              attempt: lastAttempt,
              chunkSize: chunk.size
            }
          );
        }
        return;
      }

      parts.push({ partNumber: partNum, etag });
      loadedBeforeCurrentPart += chunk.size;
    }

    if (isAborted) return;

    // 5. Complete multipart upload with sorted parts list
    parts.sort((a, b) => a.partNumber - b.partNumber);

    if (options.onProgress) {
      options.onProgress({
        uploadedBytes: file.size,
        totalBytes: file.size,
        percentage: 99,
        speed: "Finalizing...",
        remainingTime: "Completing R2 multipart upload...",
        uploadId: activeUploadId,
        objectKey: activeKey,
        currentPart: totalParts,
        totalParts
      });
    }

    const completeResult = await completeMultipartUpload({
      key: activeKey,
      uploadId: activeUploadId,
      parts
    });

    if (isAborted) return;

    if (!completeResult.success) {
      if (options.onError) {
        options.onError(
          "Failed to complete R2 multipart upload.",
          completeResult.error || "R2 complete request failed.",
          {
            stage: "finalizing",
            httpStatus: 500,
            httpMethod: "POST",
            endpoint: `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/complete`,
            responseBody: sanitizeSecrets(JSON.stringify(completeResult.raw || {})),
            errorCode: "MULTIPART_COMPLETE_FAILED",
            errorClassification: "HTTP_5XX",
            uploadId: activeUploadId,
            objectKey: activeKey
          }
        );
      }
      return;
    }

    // 6. Verify object existence in R2 directly
    if (options.onProgress) {
      options.onProgress({
        uploadedBytes: file.size,
        totalBytes: file.size,
        percentage: 99,
        speed: "Verifying...",
        remainingTime: "Checking R2 object existence...",
        uploadId: activeUploadId,
        objectKey: activeKey
      });
    }

    const checkResult = await checkR2ObjectExists(activeKey);
    if (!checkResult.exists) {
      if (options.onError) {
        options.onError(
          "Cloudflare R2 Verification Failed ❌ - Object does not exist in R2 bucket.",
          `Object "${activeKey}" was not found in R2 bucket after multipart completion.`,
          {
            stage: "verification_failed",
            httpStatus: 404,
            httpMethod: "GET",
            endpoint: `${CLOUDFLARE_WORKER_BASE}/api/r2/check?key=${encodeURIComponent(activeKey)}`,
            responseBody: JSON.stringify(checkResult),
            errorCode: "VERIFICATION_FAILED",
            errorClassification: "HTTP_404",
            uploadId: activeUploadId,
            objectKey: activeKey
          }
        );
      }
      return;
    }

    const videoUrl = buildCloudflareVideoUrl(activeKey);

    if (options.onSuccess) {
      options.onSuccess(videoUrl, activeKey, activeUploadId);
    }
  };

  startUpload().catch((err: any) => {
    if (!isAborted && options.onError) {
      options.onError(
        "Unexpected exception during Cloudflare R2 upload process.",
        err?.message || String(err),
        {
          stage: "upload_failed",
          httpStatus: 500,
          httpMethod: "POST",
          endpoint: `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/init`,
          responseBody: sanitizeSecrets(String(err)),
          errorCode: "UPLOAD_EXCEPTION",
          errorClassification: "UNKNOWN_ERROR",
          uploadId: activeUploadId,
          objectKey: activeKey
        }
      );
    }
  });

  return {
    abort: () => {
      abort();
    }
  };
}

/**
 * Verify Cloudflare video availability directly against Worker
 */
export async function verifyCloudflareVideo(objectKey: string): Promise<{ exists: boolean; videoUrl: string; contentLength?: string }> {
  try {
    const checkRes = await checkR2ObjectExists(objectKey);
    if (checkRes.exists) {
      return {
        exists: true,
        videoUrl: buildCloudflareVideoUrl(objectKey)
      };
    }
    return { exists: false, videoUrl: buildCloudflareVideoUrl(objectKey) };
  } catch (err) {
    return { exists: false, videoUrl: buildCloudflareVideoUrl(objectKey) };
  }
}

/**
 * Delete object from Cloudflare R2 via proxy or direct admin route
 */
export async function deleteFromCloudflareR2(objectKey: string): Promise<boolean> {
  try {
    const res = await fetch(`/api/cloudflare/delete?key=${encodeURIComponent(objectKey)}`, {
      method: "DELETE"
    });
    return res.ok;
  } catch (err) {
    console.error("Failed to delete Cloudflare R2 object:", err);
    return false;
  }
}

/**
 * Finalize temporary R2 object key to final production object key via backend proxy
 */
export async function finalizeCloudflareVideo(
  tmpKey: string,
  finalKey: string
): Promise<{ success: boolean; key?: string; videoUrl?: string; error?: string }> {
  try {
    const res = await fetch("/api/cloudflare/finalize", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tmpKey, finalKey })
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.success) {
      return {
        success: true,
        key: data.key || finalKey,
        videoUrl: data.videoUrl || buildCloudflareVideoUrl(finalKey)
      };
    }
    return {
      success: false,
      error: data.error || data.details || `Finalize failed with status ${res.status}`
    };
  } catch (err: any) {
    return {
      success: false,
      error: err.message || "Network error during Cloudflare video finalization"
    };
  }
}
