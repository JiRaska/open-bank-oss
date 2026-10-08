// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.iso20022;

import com.openbank.libs.xml.SecureXml;
import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Parses an untrusted camt.053 upload through the fleet-wide hardened factory ({@link SecureXml}). */
final class SecureXmlParser {
    private SecureXmlParser() {}

    static Document parse(byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        return SecureXml.parse(xml);
    }
}
