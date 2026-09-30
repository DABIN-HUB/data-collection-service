import { defineStore } from "pinia";

import { getProtocolFields, listProtocols } from "@/api/protocol.api";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

interface ProtocolState {
  loading: boolean;
  error: string;
  protocols: ProtocolSchema[];
  fieldsByProtocol: Record<string, ProtocolFieldConfig[]>;
  refreshGeneration: number;
  fieldGenerationByProtocol: Record<string, number>;
  fieldLoadingByProtocol: Record<string, boolean>;
  fieldErrorByProtocol: Record<string, string>;
}

export const useProtocolStore = defineStore("protocol", {
  state: (): ProtocolState => ({
    loading: false,
    error: "",
    protocols: [],
    fieldsByProtocol: {},
    refreshGeneration: 0,
    fieldGenerationByProtocol: {},
    fieldLoadingByProtocol: {},
    fieldErrorByProtocol: {}
  }),
  getters: {
    protocolMap: (state) => Object.fromEntries(state.protocols.map((protocol) => [protocol.protocol, protocol])),
    isFieldLoading: (state) => (protocol: string) => Boolean(state.fieldLoadingByProtocol[protocol]),
    fieldErrorFor: (state) => (protocol: string) => state.fieldErrorByProtocol[protocol] || ""
  },
  actions: {
    async refresh() {
      const requestGeneration = this.refreshGeneration + 1;
      this.refreshGeneration = requestGeneration;
      this.loading = true;
      this.error = "";
      try {
        const protocols = await listProtocols();
        if (requestGeneration !== this.refreshGeneration) {
          return;
        }
        this.protocols = protocols;
      } catch (error) {
        if (requestGeneration !== this.refreshGeneration) {
          return;
        }
        this.error = error instanceof Error ? error.message : "协议元数据加载失败";
      } finally {
        if (requestGeneration === this.refreshGeneration) {
          this.loading = false;
        }
      }
    },
    async loadFields(protocol: string) {
      const protocolCode = normalizeProtocolCode(protocol);
      if (!protocolCode || this.fieldsByProtocol[protocolCode]) {
        return;
      }
      const requestGeneration = (this.fieldGenerationByProtocol[protocolCode] || 0) + 1;
      this.fieldGenerationByProtocol[protocolCode] = requestGeneration;
      this.fieldLoadingByProtocol[protocolCode] = true;
      this.fieldErrorByProtocol[protocolCode] = "";
      try {
        const fields = await getProtocolFields(protocolCode);
        if (requestGeneration !== this.fieldGenerationByProtocol[protocolCode]) {
          return;
        }
        this.fieldsByProtocol[protocolCode] = fields;
      } catch (error) {
        if (requestGeneration !== this.fieldGenerationByProtocol[protocolCode]) {
          return;
        }
        this.fieldErrorByProtocol[protocolCode] = error instanceof Error ? error.message : "协议字段加载失败";
      } finally {
        if (requestGeneration === this.fieldGenerationByProtocol[protocolCode]) {
          this.fieldLoadingByProtocol[protocolCode] = false;
        }
      }
    }
  }
});

function normalizeProtocolCode(protocol: string): string {
  return typeof protocol === "string" ? protocol.trim() : "";
}
