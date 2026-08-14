export interface ProtocolFieldConfig {
  name: string;
  type?: string;
  label?: string;
  required?: boolean;
  defaultValue?: string;
  description?: string;
  group?: string;
  requiredWhen?: string;
  storage?: string;
  options?: string[];
}

export interface ProtocolSchema {
  protocol: string;
  title?: string;
  description?: string;
  implemented?: boolean;
  writable?: boolean;
  subscribable?: boolean;
  implementationState?: string;
  writeCapability?: string;
  subscriptionCapability?: string;
  browseCapability?: string;
  typeMode?: string;
  primaryTypeField?: string;
  platformDataTypeMode?: string;
  driverTypeEnabled?: boolean;
  driverTypeLabel?: string;
  driverTypeField?: string;
  aliases?: string[];
  connectionFields?: ProtocolFieldConfig[];
  pointAddressHints?: string[];
  dataTypes?: string[];
  driverDataTypes?: string[];
  pointFields?: ProtocolFieldConfig[];
}
