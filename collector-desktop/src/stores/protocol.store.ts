import { defineStore } from "pinia";

import { getProtocolFields, listProtocols } from "@/api/protocol.api";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

interface ProtocolState {
  loading: boolean;
  error: string;
  protocols: ProtocolSchema[];
  fieldsByProtocol: Record<string, ProtocolFieldConfig[]>;
}

export const useProtocolStore = defineStore("protocol", {
  state: (): ProtocolState => ({
    loading: false,
    error: "",
    protocols: [],
    fieldsByProtocol: {}
  }),
  getters: {
    protocolMap: (state) => Object.fromEntries(state.protocols.map((protocol) => [protocol.protocol, protocol]))
  },
  actions: {
    async refresh() {
      this.loading = true;
      this.error = "";
      try {
        this.protocols = await listProtocols();
      } catch (error) {
        this.error = error instanceof Error ? error.message : "协议元数据加载失败";
      } finally {
        this.loading = false;
      }
    },
    async loadFields(protocol: string) {
      if (!protocol || this.fieldsByProtocol[protocol]) {
        return;
      }
      try {
        this.fieldsByProtocol[protocol] = await getProtocolFields(protocol);
      } catch (error) {
        this.error = error instanceof Error ? error.message : "协议字段加载失败";
      }
    }
  }
});
