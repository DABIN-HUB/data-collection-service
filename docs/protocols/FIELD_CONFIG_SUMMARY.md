# 鍗忚瀛楁姹囨€?
鏈枃妗ｅ皢鍚勯噰闆嗗崗璁疄闄呰鍙栫殑杩炴帴閰嶇疆瀛楁缁熶竴姹囨€讳负 `case "XXX": ... break;` 鏍煎紡锛屼究浜庣洿鎺ュ鍒跺埌瀛楁閰嶇疆鐢熸垚閫昏緫涓€?
璇存槑锛?- 浠ヤ笅瀛楁鎸夊綋鍓嶅伐绋嬩唬鐮佺湡瀹炶鍙栨儏鍐垫暣鐞嗐€?- `createFieldConfig` 鍙傛暟鍚箟渚濇涓猴細瀛楁鍚嶃€佸瓧娈电被鍨嬨€佷腑鏂囪鏄庛€佹槸鍚﹀繀濉€侀粯璁ゅ€笺€佸浐瀹氭灇涓惧€笺€?- `CUSTOM_TCP` 褰撳墠浠嶄负鍗犱綅瀹炵幇锛屾病鏈夎鍙栦笓灞炶繛鎺ュ瓧娈碉紝鍥犳浠呬繚鐣欑┖鍒嗘敮璇存槑銆?
```java
case "MODBUS_TCP":
    fields.add(createFieldConfig("host", "string", "璁惧IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", true, "502", null));
    fields.add(createFieldConfig("slaveId", "number", "浠庣珯ID", true, "1", null));
    fields.add(createFieldConfig("byteOrder", "string", "瀛楄妭椤哄簭", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
    fields.add(createFieldConfig("parity", "string", "鍏煎鏍￠獙浣嶅瓧娈?, false, "none", new String[]{"none", "odd", "even"}));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X杩炴帴涓茶鐩?, false, "", null));
    fields.add(createFieldConfig("pingAddress", "string", "PLC4X Ping鍦板潃", false, "", null));
    fields.add(createFieldConfig("maxRegistersPerRequest", "number", "鍗曟鏈€澶у瘎瀛樺櫒鏁?, false, "125", null));
    fields.add(createFieldConfig("maxCoilsPerRequest", "number", "鍗曟鏈€澶х嚎鍦堟暟", false, "2000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "3000", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", false, "3000", null));
    break;

case "MODBUS_RTU":
    fields.add(createFieldConfig("slaveId", "number", "浠庣珯ID", true, "1", null));
    fields.add(createFieldConfig("serialPort", "string", "涓插彛", true, "COM1", null));
    fields.add(createFieldConfig("baudRate", "number", "娉㈢壒鐜?, true, "9600", null));
    fields.add(createFieldConfig("dataBits", "number", "鏁版嵁浣?, true, "8", null));
    fields.add(createFieldConfig("stopBits", "number", "鍋滄浣?, true, "1", null));
    fields.add(createFieldConfig("byteOrder", "string", "瀛楄妭椤哄簭", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
    fields.add(createFieldConfig("interFrameDelay", "number", "甯ч棿寤舵椂(ms)", true, "5", null));
    fields.add(createFieldConfig("parity", "string", "鏍￠獙浣?, true, "none", new String[]{"none", "odd", "even"}));
    fields.add(createFieldConfig("plc4xProtocolCode", "string", "PLC4X涓插彛椹卞姩绫诲瀷", false, "modbus-rtu", new String[]{"modbus-rtu", "modbus-ascii"}));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X杩炴帴涓茶鐩?, false, "", null));
    fields.add(createFieldConfig("maxRegistersPerRequest", "number", "鍗曟鏈€澶у瘎瀛樺櫒鏁?, false, "125", null));
    fields.add(createFieldConfig("maxCoilsPerRequest", "number", "鍗曟鏈€澶х嚎鍦堟暟", false, "2000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "3000", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", false, "3000", null));
    break;

case "SIEMENS_S7":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "102", null));
    fields.add(createFieldConfig("rack", "number", "机架号", false, "0", null));
    fields.add(createFieldConfig("slot", "number", "槽位号", false, "1", null));
    fields.add(createFieldConfig("controllerType", "select", "控制器型号", false, "S7_1200", new String[]{"S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"}));
    fields.add(createFieldConfig("pduSize", "number", "PDU大小", false, "1024", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "64", null));
    fields.add(createFieldConfig("localTsap", "number", "本地TSAP", false, "", null));
    fields.add(createFieldConfig("remoteTsap", "number", "远端TSAP", false, "", null));
    fields.add(createFieldConfig("localDeviceGroup", "select", "本地设备组", false, "", new String[]{"PG_OR_PC", "OS", "OTHERS"}));
    fields.add(createFieldConfig("remoteDeviceGroup", "select", "远端设备组", false, "", new String[]{"PG_OR_PC", "OS", "OTHERS"}));
    fields.add(createFieldConfig("ping", "boolean", "启用PLC4X PING", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("pingTime", "number", "PING间隔(s)", false, "", null));
    fields.add(createFieldConfig("retryTime", "number", "重试监测时间(s)", false, "", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    break;

case "ETHERNET_IP":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "44818", null));
    fields.add(createFieldConfig("communicationPath", "string", "通信路径", false, "[1,0]", null));
    fields.add(createFieldConfig("backplane", "number", "背板号", false, "1", null));
    fields.add(createFieldConfig("slot", "number", "槽位号", false, "0", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "64", null));
    fields.add(createFieldConfig("bigEndian", "boolean", "大端模式", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("forceUnconnectedOperation", "boolean", "强制非连接模式", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("tcpKeepAlive", "boolean", "TCP KeepAlive", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("tcpNoDelay", "boolean", "TCP NoDelay", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    break;

case "ADS":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "TCP端口", false, "48898", null));
    fields.add(createFieldConfig("targetAmsNetId", "string", "目标AMS Net ID", true, "", null));
    fields.add(createFieldConfig("targetAmsPort", "number", "目标AMS端口", true, "851", null));
    fields.add(createFieldConfig("sourceAmsNetId", "string", "源AMS Net ID", true, "", null));
    fields.add(createFieldConfig("sourceAmsPort", "number", "源AMS端口", true, "", null));
    fields.add(createFieldConfig("loadSymbolAndDataTypeTables", "boolean", "加载符号/类型表", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("timeoutRequest", "number", "ADS请求超时(ms)", false, "4000", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "64", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    break;

case "OPC_UA":
    fields.add(createFieldConfig("url", "string", "OPC UA绔偣鍦板潃", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpointUrl", "string", "绔偣鍦板潃鍏煎瀛楁", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpoint", "string", "绔偣鍦板潃鍒悕", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("host", "string", "涓绘満", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", false, "4840", null));
    fields.add(createFieldConfig("securityPolicy", "string", "瀹夊叏绛栫暐", true, "None", new String[]{"None", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
    fields.add(createFieldConfig("securityMode", "string", "瀹夊叏妯″紡", true, "None", new String[]{"None", "Sign", "SignAndEncrypt"}));
    fields.add(createFieldConfig("authType", "string", "璁よ瘉鏂瑰紡", true, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
    fields.add(createFieldConfig("username", "string", "鐢ㄦ埛鍚?, false, "", null));
    fields.add(createFieldConfig("password", "string", "瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "鍏煎璁よ瘉鍙傛暟", false, "{}", null));
    fields.add(createFieldConfig("requestTimeoutMs", "number", "璇锋眰瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("requestTimeout", "number", "璇锋眰瓒呮椂鍏煎瀛楁(ms)", false, "5000", null));
    fields.add(createFieldConfig("connectTimeoutMs", "number", "杩炴帴瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("connectTimeout", "number", "杩炴帴瓒呮椂鍏煎瀛楁(ms)", false, "5000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("subscriptionInterval", "number", "璁㈤槄鍙戝竷闂撮殧(ms)", false, "1000", null));
    fields.add(createFieldConfig("namespaceUri", "string", "鍛藉悕绌洪棿URI", false, "", null));
    fields.add(createFieldConfig("clientCertPath", "string", "瀹㈡埛绔瘉涔﹁矾寰?, false, "", null));
    fields.add(createFieldConfig("clientCertPassword", "string", "瀹㈡埛绔瘉涔﹀瘑鐮?, false, "", null));
    fields.add(createFieldConfig("trustAllServerCert", "boolean", "鏄惁淇′换鎵€鏈夋湇鍔＄璇佷功", false, "false", new String[]{"true", "false"}));
    break;

case "OPC_UA_PLC4X":
    fields.add(createFieldConfig("url", "string", "OPC UA端点地址", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpoint", "string", "端点地址别名", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "4840", null));
    fields.add(createFieldConfig("discovery", "boolean", "是否启用 discovery", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authType", "string", "认证方式", false, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
    fields.add(createFieldConfig("securityPolicy", "string", "安全策略", false, "NONE", new String[]{"NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
    fields.add(createFieldConfig("messageSecurity", "string", "消息安全模式", false, "NONE", new String[]{"NONE", "SIGN", "SIGN_ENCRYPT"}));
    fields.add(createFieldConfig("securityMode", "string", "消息安全模式兼容字段", false, "NONE", new String[]{"NONE", "Sign", "SignAndEncrypt"}));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "认证参数兼容字段", false, "{}", null));
    fields.add(createFieldConfig("keyStoreFile", "string", "客户端密钥库文件", false, "", null));
    fields.add(createFieldConfig("keyStoreType", "string", "客户端密钥库类型", false, "pkcs12", null));
    fields.add(createFieldConfig("keyStorePassword", "string", "客户端密钥库密码", false, "", null));
    fields.add(createFieldConfig("clientCertPath", "string", "客户端证书兼容字段", false, "", null));
    fields.add(createFieldConfig("clientCertPassword", "string", "客户端证书密码兼容字段", false, "", null));
    fields.add(createFieldConfig("trustStoreFile", "string", "信任库文件", false, "", null));
    fields.add(createFieldConfig("trustStoreType", "string", "信任库类型", false, "pkcs12", null));
    fields.add(createFieldConfig("trustStorePassword", "string", "信任库密码", false, "", null));
    fields.add(createFieldConfig("serverCertificateFile", "string", "服务端证书文件", false, "", null));
    fields.add(createFieldConfig("endpointHost", "string", "端点主机覆盖", false, "", null));
    fields.add(createFieldConfig("endpointPort", "number", "端点端口覆盖", false, "", null));
    fields.add(createFieldConfig("channelLifetime", "number", "安全通道生命周期(ms)", false, "3600000", null));
    fields.add(createFieldConfig("sessionTimeout", "number", "会话超时(ms)", false, "120000", null));
    fields.add(createFieldConfig("negotiationTimeout", "number", "握手超时(ms)", false, "60000", null));
    fields.add(createFieldConfig("connectTimeoutMs", "number", "连接超时兼容字段(ms)", false, "60000", null));
    fields.add(createFieldConfig("connectTimeout", "number", "连接超时兼容字段(ms)", false, "60000", null));
    fields.add(createFieldConfig("requestTimeout", "number", "请求超时(ms)", false, "30000", null));
    fields.add(createFieldConfig("requestTimeoutMs", "number", "请求超时兼容字段(ms)", false, "30000", null));
    fields.add(createFieldConfig("subscriptionInterval", "number", "订阅周期(ms)", false, "1000", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "100", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    break;

case "OPC_DA":
    fields.add(createFieldConfig("url", "string", "妗ユ帴鍦板潃鎴朞PC DA璁块棶鍦板潃", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("host", "string", "OPC DA涓绘満", true, "127.0.0.1", null));
    fields.add(createFieldConfig("serverProgId", "string", "OPC DA鏈嶅姟ProgID", true, "Matrikon.OPC.Simulation.1", null));
    fields.add(createFieldConfig("progId", "string", "ProgID鍏煎瀛楁", false, "Matrikon.OPC.Simulation.1", null));
    fields.add(createFieldConfig("clsid", "string", "CLSID鍏煎瀛楁", false, "", null));
    fields.add(createFieldConfig("username", "string", "鐢ㄦ埛鍚?, false, "", null));
    fields.add(createFieldConfig("password", "string", "瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("domain", "string", "Windows鍩?, false, "", null));
    fields.add(createFieldConfig("requestTimeout", "number", "璇锋眰瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("updateRate", "number", "璁㈤槄鍒锋柊鍛ㄦ湡(ms)", false, "1000", null));
    fields.add(createFieldConfig("bridgeMode", "string", "妗ユ帴妯″紡", true, "INMEMORY", new String[]{"INMEMORY", "HTTP"}));
    fields.add(createFieldConfig("bridge-mode", "string", "妗ユ帴妯″紡鍏煎瀛楁", false, "INMEMORY", new String[]{"INMEMORY", "HTTP"}));
    fields.add(createFieldConfig("opcDaBridgeMode", "string", "妗ユ帴妯″紡鍒悕", false, "INMEMORY", new String[]{"INMEMORY", "HTTP"}));
    fields.add(createFieldConfig("bridgeBaseUrl", "string", "妗ユ帴鍩虹鍦板潃", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("bridge-url", "string", "妗ユ帴鍦板潃鍏煎瀛楁", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("opcDaBridgeUrl", "string", "妗ユ帴鍦板潃鍒悕", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("bridgeToken", "string", "妗ユ帴璁块棶浠ょ墝", false, "", null));
    fields.add(createFieldConfig("bridge-token", "string", "妗ユ帴浠ょ墝鍏煎瀛楁", false, "", null));
    fields.add(createFieldConfig("opcDaBridgeToken", "string", "妗ユ帴浠ょ墝鍒悕", false, "", null));
    fields.add(createFieldConfig("bridgeRetryCount", "number", "妗ユ帴閲嶈瘯娆℃暟", false, "1", null));
    fields.add(createFieldConfig("bridgeRetryBackoffMs", "number", "妗ユ帴閲嶈瘯閫€閬?ms)", false, "200", null));
    break;

case "IEC104":
    fields.add(createFieldConfig("host", "string", "璁惧IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", true, "2404", null));
    fields.add(createFieldConfig("slaveId", "number", "鍏叡鍦板潃(commonAddress)", true, "1", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", true, "5000", null));
    break;

case "IEC61850":
    fields.add(createFieldConfig("host", "string", "璁惧IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "MMS绔彛", true, "102", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", true, "10000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "10000", null));
    break;

case "MQTT":
    fields.add(createFieldConfig("url", "string", "Broker瀹屾暣鍦板潃", false, "tcp://127.0.0.1:1883", null));
    fields.add(createFieldConfig("brokerUrl", "string", "Broker鍦板潃鍏煎瀛楁", false, "tcp://127.0.0.1:1883", null));
    fields.add(createFieldConfig("host", "string", "Broker涓绘満", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "Broker绔彛", false, "1883", null));
    fields.add(createFieldConfig("clientId", "string", "瀹㈡埛绔疘D", true, "device_mqtt", null));
    fields.add(createFieldConfig("version", "string", "MQTT鐗堟湰", true, "v5", new String[]{"v5", "v3"}));
    fields.add(createFieldConfig("username", "string", "鐢ㄦ埛鍚?, false, "", null));
    fields.add(createFieldConfig("password", "string", "瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("sslEnabled", "boolean", "鏄惁鍚敤SSL", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("subscribeTopics", "string", "榛樿璁㈤槄涓婚鍒楄〃", false, "devices/${deviceId}/#", null));
    fields.add(createFieldConfig("subscribeQos", "number", "榛樿璁㈤槄QoS", false, "1", new String[]{"0", "1", "2"}));
    fields.add(createFieldConfig("publishTopic", "string", "榛樿鍙戝竷涓婚", false, "devices/${deviceId}/data", null));
    fields.add(createFieldConfig("publishQos", "number", "鍙戝竷QoS", false, "1", new String[]{"0", "1", "2"}));
    fields.add(createFieldConfig("retained", "boolean", "鍙戝竷淇濈暀鏍囪", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("cleanSession", "boolean", "鏄惁娓呯悊浼氳瘽", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("autoReconnect", "boolean", "鏄惁鑷姩閲嶈繛", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("connectTimeout", "number", "杩炴帴瓒呮椂(ms)", false, "10000", null));
    fields.add(createFieldConfig("heartbeatInterval", "number", "淇濇椿闂撮殧(ms)", false, "60000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("sessionExpiryInterval", "number", "浼氳瘽杩囨湡闂撮殧(s)", false, "86400", null));
    fields.add(createFieldConfig("receiveMaximum", "number", "鎺ユ敹绐楀彛澶у皬", false, "65535", null));
    fields.add(createFieldConfig("willTopic", "string", "閬楀槺涓婚", false, "", null));
    fields.add(createFieldConfig("willMessage", "string", "閬楀槺娑堟伅", false, "", null));
    fields.add(createFieldConfig("willQos", "number", "閬楀槺QoS", false, "0", new String[]{"0", "1", "2"}));
    fields.add(createFieldConfig("willRetained", "boolean", "閬楀槺淇濈暀鏍囪", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authTopic", "string", "棰濆璁よ瘉涓婚", false, "", null));
    fields.add(createFieldConfig("messageProperties", "object", "MQTT v5娑堟伅灞炴€?, false, "{}", null));
    fields.add(createFieldConfig("maxPendingMessages", "number", "鏈€澶х紦瀛樻秷鎭暟", false, "5000", null));
    fields.add(createFieldConfig("dispatchBatchSize", "number", "鎵归噺鍒嗗彂澶у皬", false, "1", null));
    fields.add(createFieldConfig("dispatchFlushInterval", "number", "鎵归噺鍒嗗彂鍒锋柊闂撮殧(ms)", false, "0", null));
    fields.add(createFieldConfig("overflowStrategy", "string", "娑堟伅婧㈠嚭绛栫暐", false, "BLOCK", new String[]{"BLOCK", "DROP_LATEST", "DROP_OLDEST"}));
    fields.add(createFieldConfig("productKey", "string", "浜у搧鏍囪瘑", false, "", null));
    fields.add(createFieldConfig("deviceSecret", "string", "璁惧瀵嗛挜", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "鎵╁睍璁よ瘉鍙傛暟", false, "{}", null));
    break;

case "SNMP":
    fields.add(createFieldConfig("host", "string", "璁惧IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", true, "161", null));
    fields.add(createFieldConfig("community", "string", "鍥綋瀛?, true, "public", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("snmpRetries", "number", "閲嶈瘯娆℃暟", false, "1", null));
    fields.add(createFieldConfig("snmpVersion", "string", "SNMP鐗堟湰", true, "2c", new String[]{"1", "2c", "3"}));
    fields.add(createFieldConfig("snmpSecurityName", "string", "SNMPv3瀹夊叏鐢ㄦ埛鍚?, false, "", null));
    fields.add(createFieldConfig("snmpSecurityLevel", "string", "SNMPv3瀹夊叏绾у埆", false, "authPriv", new String[]{"noAuthNoPriv", "authNoPriv", "authPriv"}));
    fields.add(createFieldConfig("snmpAuthProtocol", "string", "SNMPv3璁よ瘉鍗忚", false, "SHA", new String[]{"MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"}));
    fields.add(createFieldConfig("snmpAuthPassword", "string", "SNMPv3璁よ瘉瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("snmpPrivProtocol", "string", "SNMPv3鍔犲瘑鍗忚", false, "AES128", new String[]{"DES", "AES128", "AES192", "AES256", "NONE"}));
    fields.add(createFieldConfig("snmpPrivPassword", "string", "SNMPv3鍔犲瘑瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("snmpContextName", "string", "涓婁笅鏂囧悕绉?, false, "", null));
    fields.add(createFieldConfig("snmpContextEngineId", "string", "涓婁笅鏂囧紩鎿嶪D", false, "", null));
    break;

case "COAP":
    fields.add(createFieldConfig("url", "string", "CoAP鍩虹鍦板潃", false, "coap://127.0.0.1:5683", null));
    fields.add(createFieldConfig("host", "string", "璁惧IP", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", false, "5683", null));
    fields.add(createFieldConfig("scheme", "string", "鍗忚鏂规", false, "coap", new String[]{"coap", "coaps"}));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", false, "3000", null));
    fields.add(createFieldConfig("maxPendingMessages", "number", "鏈€澶ф帓闃熻姹傛暟", false, "1024", null));
    fields.add(createFieldConfig("dispatchBatchSize", "number", "鎵归噺鍒嗗彂澶у皬", false, "1", null));
    fields.add(createFieldConfig("dispatchFlushInterval", "number", "鎵归噺鍒嗗彂鍒锋柊闂撮殧(ms)", false, "0", null));
    fields.add(createFieldConfig("overflowStrategy", "string", "璇锋眰婧㈠嚭绛栫暐", false, "BLOCK", new String[]{"BLOCK", "DROP_LATEST", "DROP_OLDEST"}));
    break;

case "HTTP":
    fields.add(createFieldConfig("url", "string", "HTTP鍩虹鍦板潃", false, "http://127.0.0.1:8080", null));
    fields.add(createFieldConfig("host", "string", "涓绘満", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", false, "8080", null));
    fields.add(createFieldConfig("sslEnabled", "boolean", "鏄惁鍚敤HTTPS", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("path", "string", "鍩虹璺緞", false, "", null));
    fields.add(createFieldConfig("headers", "object", "鑷畾涔夎姹傚ご", false, "{}", null));
    fields.add(createFieldConfig("queryParams", "object", "鏌ヨ鍙傛暟", false, "{}", null));
    fields.add(createFieldConfig("connectTimeout", "number", "杩炴帴瓒呮椂(ms)", false, "10000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("method", "string", "鍙戦€佹柟娉?, false, "POST", new String[]{"GET", "POST", "PUT", "DELETE", "HEAD"}));
    fields.add(createFieldConfig("sendEndpoint", "string", "鍙戦€佹帴鍙ｈ矾寰?, false, "/api/data", null));
    fields.add(createFieldConfig("receiveEndpoint", "string", "鎺ユ敹鎺ュ彛璺緞", false, "/api/receive", null));
    fields.add(createFieldConfig("receiveMethod", "string", "鎺ユ敹鏂规硶", false, "GET", new String[]{"GET", "POST", "PUT", "DELETE"}));
    fields.add(createFieldConfig("healthCheckPath", "string", "鍋ュ悍妫€鏌ヨ矾寰?, false, "/health", null));
    fields.add(createFieldConfig("heartbeatEndpoint", "string", "蹇冭烦鎺ュ彛璺緞", false, "/health", null));
    fields.add(createFieldConfig("username", "string", "鐢ㄦ埛鍚?, false, "", null));
    fields.add(createFieldConfig("password", "string", "瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("authToken", "string", "Bearer浠ょ墝", false, "", null));
    fields.add(createFieldConfig("authEndpoint", "string", "璁よ瘉鎺ュ彛璺緞", false, "/api/auth", null));
    fields.add(createFieldConfig("authMethod", "string", "璁よ瘉鏂规硶", false, "POST", new String[]{"GET", "POST", "PUT", "DELETE"}));
    fields.add(createFieldConfig("proxyHost", "string", "浠ｇ悊涓绘満", false, "", null));
    fields.add(createFieldConfig("proxyPort", "number", "浠ｇ悊绔彛", false, "8080", null));
    fields.add(createFieldConfig("deviceSecret", "string", "璁惧瀵嗛挜", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "鎵╁睍璁よ瘉鍙傛暟", false, "{}", null));
    break;

case "WEBSOCKET":
    fields.add(createFieldConfig("url", "string", "WebSocket鍦板潃", false, "ws://127.0.0.1:8080/ws", null));
    fields.add(createFieldConfig("host", "string", "涓绘満", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "绔彛", false, "8080", null));
    fields.add(createFieldConfig("sslEnabled", "boolean", "鏄惁鍚敤WSS", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("path", "string", "杩炴帴璺緞", false, "/ws", null));
    fields.add(createFieldConfig("queryParams", "object", "鏌ヨ鍙傛暟", false, "{}", null));
    fields.add(createFieldConfig("headers", "object", "鑷畾涔夎姹傚ご", false, "{}", null));
    fields.add(createFieldConfig("connectTimeout", "number", "杩炴帴瓒呮椂(ms)", false, "10000", null));
    fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("writeTimeout", "number", "鍐欏叆瓒呮椂(ms)", false, "5000", null));
    fields.add(createFieldConfig("username", "string", "鐢ㄦ埛鍚?, false, "", null));
    fields.add(createFieldConfig("password", "string", "瀵嗙爜", false, "", null));
    fields.add(createFieldConfig("authToken", "string", "Bearer浠ょ墝", false, "", null));
    fields.add(createFieldConfig("subprotocol", "string", "瀛愬崗璁?, false, "collector-v1", null));
    fields.add(createFieldConfig("binaryMode", "boolean", "鏄惁浜岃繘鍒舵敹鍙?, false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("heartbeatInterval", "number", "蹇冭烦闂撮殧(ms)", false, "60000", null));
    fields.add(createFieldConfig("heartbeatMessage", "string", "蹇冭烦娑堟伅", false, "ping", null));
    fields.add(createFieldConfig("heartbeatUsePing", "boolean", "鏄惁浣跨敤Ping甯?, false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authWaitResponse", "boolean", "璁よ瘉鍚庢槸鍚︾瓑寰呭搷搴?, false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("productKey", "string", "浜у搧鏍囪瘑", false, "", null));
    fields.add(createFieldConfig("deviceSecret", "string", "璁惧瀵嗛挜", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "鎵╁睍璁よ瘉鍙傛暟", false, "{}", null));
    break;

case "CUSTOM_TCP":
    break;
```
