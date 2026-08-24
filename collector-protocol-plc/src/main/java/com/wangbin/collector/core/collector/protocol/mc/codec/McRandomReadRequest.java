package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 承载当前模块的数据传输内容。
 */
public class McRandomReadRequest {

    private final List<McAddress> wordAddresses;

    /**
     * 创建当前组件实例。
     */
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
