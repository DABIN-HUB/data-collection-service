package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class McRandomReadRequest {

    private final List<McAddress> wordAddresses;

    public McRandomReadRequest(List<McAddress> wordAddresses) {
        List<McAddress> safeAddresses = wordAddresses == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(wordAddresses));
        this.wordAddresses = safeAddresses;
    }

    public List<McAddress> getWordAddresses() {
        return wordAddresses;
    }

    public int getWordAddressCount() {
        return wordAddresses.size();
    }
}
