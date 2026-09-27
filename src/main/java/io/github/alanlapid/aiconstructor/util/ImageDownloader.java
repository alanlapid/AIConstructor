package io.github.alanlapid.aiconstructor.util;

import io.github.alanlapid.aiconstructor.ai.ImageData;
import io.github.alanlapid.aiconstructor.ai.PlanGenerator.GenerationException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Downloads a reference photo with a size limit. Blocking: call from an async task. */
public final class ImageDownloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private ImageDownloader() {
    }

    public static ImageData download(String url, int maxMegabytes) throws GenerationException, InterruptedException {
        URI uri = checkedUri(url);
        long max = (long) maxMegabytes * 1024 * 1024;
        try {
            HttpResponse<InputStream> response = null;
            // Follow redirects manually so every hop is checked against internal addresses.
            for (int hop = 0; hop <= 3; hop++) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "Mozilla/5.0 AIConstructor")
                        .GET()
                        .build();
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status < 300 || status >= 400) {
                    break;
                }
                response.body().close();
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || hop == 3) {
                    throw new GenerationException("Could not download the photo (too many redirects).");
                }
                uri = checkedUri(uri.resolve(location).toString());
            }
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new GenerationException("Could not download the photo (HTTP " + response.statusCode() + ").");
                }
                byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
                if (data.length > max) {
                    throw new GenerationException("The photo is larger than " + maxMegabytes + " MB.");
                }
                String mime = ImageData.sniffMime(data);
                if (mime == null) {
                    throw new GenerationException("The link is not a PNG, JPG or WEBP image (use the direct image link).");
                }
                return new ImageData(data, mime);
            }
        } catch (IOException e) {
            throw new GenerationException("Could not download the photo: " + e.getMessage());
        }
    }

    private static URI checkedUri(String url) throws GenerationException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new GenerationException("The link is not valid.");
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null) {
            throw new GenerationException("The link must start with http:// or https://");
        }
        // Players control this URL: never let it reach the server's own network.
        if (isInternal(uri.getHost())) {
            throw new GenerationException("That address is not allowed.");
        }
        return uri;
    }

    private static boolean isInternal(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
