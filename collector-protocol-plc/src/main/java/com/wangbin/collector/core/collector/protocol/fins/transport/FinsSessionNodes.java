package com.wangbin.collector.core.collector.protocol.fins.transport;

/** FINS 会话实际使用的本地节点和目标节点；TCP 使用服务端协商结果。 */
public record FinsSessionNodes(int sourceNode, int destinationNode) {
    public FinsSessionNodes {
        if (sourceNode < 0 || sourceNode > 255 || destinationNode < 0 || destinationNode > 255) {
            throw new IllegalArgumentException("FINS node must be between 0 and 255");
        }
    }
}
