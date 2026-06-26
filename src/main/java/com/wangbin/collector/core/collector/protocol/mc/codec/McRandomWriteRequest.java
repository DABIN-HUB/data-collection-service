package com.wangbin.collector.core.collector.protocol.mc.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class McRandomWriteRequest {

    private final List<McRandomWriteItem> wordItems;

    public McRandomWriteRequest(List<McRandomWriteItem> wordItems) {
        List<McRandomWriteItem> safeItems = wordItems == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(wordItems));
        this.wordItems = safeItems;
    }

    public List<McRandomWriteItem> getWordItems() {
        return wordItems;
    }

    public int getWordItemCount() {
        return wordItems.size();
    }
}
