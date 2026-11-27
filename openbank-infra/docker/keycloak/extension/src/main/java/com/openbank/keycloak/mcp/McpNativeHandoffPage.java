// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.keycloak.mcp;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.UUID;

final class McpNativeHandoffPage {
    // Standalone Lípa mascot derived from the first-party landing artwork.
    private static final String LIPA_MASCOT = asset("lipa-mascot.webp");
    private McpNativeHandoffPage() {
    }

    static Response render(String action, String client, McpNativeHandoff.Issued issued)
            throws IOException, WriterException {
        String nonce = UUID.randomUUID().toString();
        String input = issued == null ? "" :
                "<input type=\"hidden\" name=\"handoff\" value=\"" + issued.formValue() + "\">";
        String html = TEMPLATE.formatted(nonce, STYLES, LIPA_MASCOT, McpWithheldCodeAuthenticator.escape(client),
                issued == null ? WAITING : qrPanel(issued), McpWithheldCodeAuthenticator.escape(action), input, nonce);
        return Response.ok(html, MediaType.TEXT_HTML_TYPE.withCharset("utf-8"))
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        "default-src 'none'; img-src data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; "
                                + "style-src 'nonce-" + nonce + "'; script-src 'nonce-" + nonce + "'")
                .build();
    }

    private static String qrPanel(McpNativeHandoff.Issued issued) throws IOException, WriterException {
        String link = issued.appLink();
        var matrix = new MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 240, 240);
        var image = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", image);
        return """
                <figure>
                  <img width="240" height="240" alt="QR kód pro otevření aplikace OpenBank"
                       src="data:image/png;base64,%s">
                  <figcaption>Naskenujte fotoaparátem telefonu.
                    <span lang="en">Scan with your phone camera.</span>
                  </figcaption>
                </figure>
                <a class="primary" href="%s">Otevřít OpenBank <span lang="en">Open OpenBank</span></a>
                """.formatted(Base64.getEncoder().encodeToString(image.toByteArray()),
                        McpWithheldCodeAuthenticator.escape(link));
    }

    private static String asset(String name) {
        try (var image = McpNativeHandoffPage.class.getResourceAsStream(name)) {
            if (image == null) {
                throw new IllegalStateException("Missing packaged OpenBank artwork");
            }
            return Base64.getEncoder().encodeToString(image.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String WAITING = """
            <p class="waiting">Pokračujte ve schválení v aplikaci. Pokud jste ji ještě neotevřeli,
              začněte znovu v asistentovi.
              <span lang="en">Continue in the app. If you haven't opened it yet, restart from your assistant.</span>
            </p>
            """;

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="cs"><head><meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Propojení asistenta · OpenBank</title>
              <style nonce="%s">%s</style>
            </head><body>
              <main aria-labelledby="mcp-title">
                <header>
                  <div>
                    <p class="brand">OpenBank</p>
                    <h1 id="mcp-title">Propojení AI asistenta</h1>
                    <p class="subtitle" lang="en">Connect your AI assistant</p>
                  </div>
                  <div class="art" aria-hidden="true">
                    <img alt="" src="data:image/webp;base64,%s">
                  </div>
                </header>
                <p class="assistant"><span>Asistent <span lang="en">Assistant</span></span> <strong>%s</strong></p>
                <p>V aplikaci OpenBank vyberte účty a schvalte, k čemu bude mít asistent přístup.
                  <span lang="en">In the OpenBank app, select accounts and approve what the assistant can access.</span>
                </p>
                %s
                <form id="native-mcp-check" method="post" action="%s">
                  %s
                  <button type="submit" name="check" value="1">Pokračovat <span lang="en">Continue</span></button>
                  <button type="submit" name="cancel" value="1">Zrušit <span lang="en">Cancel</span></button>
                </form>
              </main>
              <script nonce="%s">setTimeout(function(){
                document.getElementById('native-mcp-check').requestSubmit();
              },3000);</script>
            </body></html>
            """;

    private static final String STYLES = """
            :root {
              color-scheme: light;
              font-family: system-ui, sans-serif;
              color: #122147;
              background: #ecf4fe;
            }
            * { box-sizing: border-box; }
            body { margin: 0; padding: clamp(.75rem, 4vw, 2rem); }
            main {
              max-width: 31rem; margin: 0 auto; padding: clamp(1rem, 5vw, 2.25rem);
              background: #fff; border: 1px solid #dcdaf2; border-radius: 1.25rem;
              box-shadow: 0 .75rem 2.5rem #25215b16;
            }
            header {
              display: flex; align-items: center; justify-content: space-between; gap: .75rem;
              border-bottom: 1px solid #dce4f6; padding-bottom: 1rem;
            }
            .art { flex: 0 0 clamp(4.75rem, 25vw, 7.5rem); aspect-ratio: 1; }
            .art img { display: block; inline-size: 100%; block-size: 100%; object-fit: contain; }
            .brand { margin: 0 0 .65rem; color: #2357aa; font-size: 1rem; font-weight: 750; }
            h1 { margin: 0; font-size: clamp(1.65rem, 6vw, 2.35rem); line-height: 1.08; letter-spacing: -.03em; }
            p { line-height: 1.45; }
            p [lang=en], .subtitle, figcaption [lang=en] { display: block; color: #494369; }
            .subtitle { margin: .5rem 0 0; }
            .assistant { background: #f2f0fd; border-radius: .7rem; padding: .6rem .82rem; overflow-wrap: anywhere; }
            .assistant > span { display: inline; margin-right: .45rem; }
            .assistant [lang=en] { display: inline; font-size: .85rem; }
            figure { text-align: center; margin: 1rem 0 .8rem; }
            figure img {
              display: block; inline-size: min(15rem, 70vw); block-size: auto;
              margin: 0 auto .55rem; background: white; border: 1px solid #dcdaf2; border-radius: .75rem;
            }
            figcaption { font-size: .88rem; }
            .primary, button {
              display: block; min-height: 2.75rem; text-align: center; border-radius: .65rem;
              line-height: 1.26; font: inherit; font-weight: 650;
            }
            .primary { background: #0c55b0; color: white; padding: .65rem 1rem; text-decoration: none; }
            .primary:hover { background: #16447d; }
            .primary [lang=en], button [lang=en] { display: block; font-size: .85rem; font-weight: 500; }
            form { display: flex; flex-wrap: wrap; gap: .55rem; margin-top: 1rem; }
            button { flex: 1 1 7rem; cursor: pointer; padding: .42rem .7rem; border: 1px solid #dcdaf2;
                     background: #fff; color: #16447d; }
            button:hover { background: #f2f0fd; }
            :focus-visible { outline: 3px solid #143d75; outline-offset: 3px; }
            """;
}
