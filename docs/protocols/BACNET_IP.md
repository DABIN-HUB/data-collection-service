# BACNET_IP

## 褰撳墠鐘舵€?
`BACNET_IP` 宸插畬鎴愭鏋舵帴鍏ワ紝骞朵笖宸茬粡涓嶆槸绌哄３鍗忚銆?
褰撳墠宸茬粡钀藉湴锛?
- `ProtocolType`銆佸崗璁埆鍚嶅綊涓€鍖栥€乨escriptor銆乧ollector factory銆乧onnection factory銆乿alidator 宸叉帴鍏ャ€?- 鎺у埗鍙板墠绔彲閫氳繃 `/api/protocols/BACNET_IP` 鍜?`/api/protocols/BACNET_IP/fields` 鑾峰彇 BACnet/IP 閰嶇疆瀛楁銆?- 宸插畬鎴愮湡瀹?`UDP` 杩炴帴閫傞厤鍣紝涓嶅啀鏄崰浣嶅璞°€?- 宸插畬鎴愮湡瀹炶閾捐矾锛?  - `ReadProperty`
  - `ReadPropertyMultiple`
  - 鍗曠偣璇?`readPoint`
  - 鎵归噺璇?`readPoints`
  - `ReadPropertyMultiple` 澶辫触鑷姩鍥為€€閫愮偣 `ReadProperty`
- 宸插畬鎴愬熀纭€ primitive 绫诲瀷鏀寔锛?  - `REAL`
  - `DOUBLE`
  - `BOOLEAN`
  - `STRING`
  - `ENUMERATED`
  - `UNSIGNED / SIGNED`
- 宸插畬鎴?`useWhoIsDiscovery=true` 鏃剁殑杩炴帴闃舵 `Who-Is / I-Am` 鍙戠幇銆?- 宸插畬鎴愬亣璁惧闆嗘垚娴嬭瘯锛屼笉鍐嶅彧鏈?schema/楠ㄦ灦绾ф祴璇曘€?
褰撳墠浠嶆湭瀹屾垚锛?
- `WriteProperty`
- `WritePropertyMultiple`
- `SubscribeCOV / SubscribeCOVProperty`
- `executeCommand`
- `BBMD / Foreign Device Registration`
- 鍒嗘鎶ユ枃
- constructed / array / sequence 绫诲瀷鐨勯€氱敤 `ANY` 瑙ｇ爜

褰撳墠瀵瑰浜や粯鍙ｅ緞锛?
- `BACnet/IP P0` 宸插彲鐢ㄤ簬 `UDP` 杞鍨?`ReadProperty / ReadPropertyMultiple` 閲囬泦銆?- 涓嶅缓璁幇鍦烘壙璇哄啓鍏ャ€乣COV`銆乣BBMD`銆佽法瀛愮綉鑷姩鍙戠幇銆佸鏉傛暟缁?瀵硅薄灞炴€с€?
## 宸插畬鎴愯兘鍔?
### 1. 鐪熷疄杩炴帴

- `BacnetIpConnectionAdapter` 宸插畬鎴愮湡瀹?`UDP socket` 寤鸿繛銆?- 褰撳墠鐢熸晥鐨勮繛鎺ュ瓧娈碉細
  - `host`
  - `port`
  - `localBindHost`
  - `localBindPort`
  - `remoteDeviceInstance`
  - `useWhoIsDiscovery`
  - `readTimeout`
  - `timeout`
- `useWhoIsDiscovery=true` 鏃讹紝杩炴帴闃舵浼氬厛鍙?`Who-Is`锛岃姹傛敹鍒扮洰鏍?`remoteDeviceInstance` 鐨?`I-Am` 鍚庢墠璁や负鍙戠幇鎴愬姛銆?
### 2. 鐪熷疄璇婚摼璺?
- `BacnetIpCollector` 宸叉帴鍏ワ細
  - `readPoint`
  - `readPoints`
- 褰撳墠宸叉敮鎸侊細
  - `Confirmed ReadProperty`
  - `Confirmed ReadPropertyMultiple`
  - `ComplexACK(ReadPropertyAck)`
  - `ComplexACK(ReadPropertyMultipleAck)`
- `ReadPropertyMultiple` 褰撳墠鎸夆€滃悓瀵硅薄澶氬睘鎬у悎骞?+ `maxPropertiesPerRequest` 鍒囧垎鈥濇墽琛屻€?- `ReadPropertyMultiple` 琚澶囨嫆缁濇垨澶辫触鏃讹紝浼氳嚜鍔ㄥ洖閫€鍒伴€愮偣 `ReadProperty`锛岄伩鍏嶆暣鎵圭偣浣嶄笉鍙敤銆?- 瓒呮椂銆乻ocket 寮傚父銆佸崗璁ご寮傚父浼氳Е鍙戣繛鎺ュけ鏁堝垽瀹氾紝collector 鐘舵€佷細鎵撴垚 `DISCONNECTED`銆?
### 3. 鏁版嵁澶勭悊杈圭晫

- BACnet 瀛楃涓插睘鎬т笉浼氬啀璇蛋鏁板€艰浆鎹€?- 褰撳墠宸插浠ヤ笅绫诲瀷鍋氬畨鍏ㄥ鐞嗭細
  - 鏁板€煎瀷璧?`convertData(...)`
  - `STRING` 閫忎紶
  - `BOOLEAN` 杩斿洖甯冨皵
  - `ENUMERATED` 杩斿洖鏁存暟
- 缁撴灉浠嶇粺涓€杩涘叆锛?  - `ProcessResult`
  - `DataQualityProcessor`
  - `lastProcessResults`

## 鏈畬鎴愬姛鑳芥竻鍗?
### 楂樹紭鍏堢骇

1. `WriteProperty`
   - 鍓嶇瀛楁閲屽凡鏈?`writePriority` 绛夐鐣欙紝浣嗗簳灞傛湭鍏戠幇銆?   - 涓嶅缓璁幇鍦烘壙璇哄彲鎺у啓鍥炪€?2. `executeCommand`
   - 杩樻湭瀹炵幇 `who_is / read_property / read_property_multiple / discover_objects / diagnostic` 杩欑被璇婃柇鍛戒护鍏ュ彛銆?
### 涓紭鍏堢骇

3. `SubscribeCOV / SubscribeCOVProperty`
   - 褰撳墠 `covEnabled` 鍙槸 schema 鍜?validator 宸叉帴鍏ャ€?   - 杩樻病鏈夌湡瀹炶闃呫€侀€氱煡鎺ユ敹銆佹柇绾胯ˉ璁㈤槄銆?4. constructed / array / sequence 绫诲瀷瑙ｇ爜
   - 褰撳墠鍙敮鎸?primitive `ANY`銆?   - 鍍?`objectList`銆乣priorityArray`銆佸鏉傚璞″睘鎬ц繕涓嶈兘鎵胯銆?5. `ReadPropertyMultiple` 璇昏鍒掍紭鍖?   - 褰撳墠宸插仛鍚屽璞¤仛鍚堛€佹壒閲忓垏鍒嗐€佸け璐ュ洖閫€銆?   - 杩樻病鏈夊仛鏇村己鐨勮法瀵硅薄鑱氬悎鍜屽姩鎬佹€ц兘浼樺寲銆?
### 浣庝紭鍏堢骇浣嗛珮鐜板満椋庨櫓

6. `BBMD / Foreign Device Registration`
   - 瀛楁宸查鐣欙細
     - `bbmdHost`
     - `bbmdPort`
     - `foreignDeviceTtlSeconds`
   - 浠ｇ爜鏈疄鐜帮紝涓嶅缓璁幇鍦烘壙璇鸿法瀛愮綉 BACnet/IP銆?7. 鍒嗘鎶ユ枃
   - 褰撳墠 `ComplexACK` 浠呮敮鎸侀潪鍒嗘銆?   - 澶у璞°€佸ぇ鏁扮粍銆佸ぇ鍝嶅簲鍦烘櫙鍙兘澶辫触銆?8. 鏇村畬鏁寸殑鍙戠幇鑳藉姏
   - 褰撳墠 `Who-Is / I-Am` 鍙仛浜嗏€滄寜鐩爣瀹炰緥鍙峰彂鐜板崟璁惧鈥濄€?   - 杩樻病鏈夊叏缃戞壂鎻忋€佸璞℃灇涓俱€佸彂鐜扮紦瀛樻不鐞嗐€?
## 褰撳墠鍙氦浠樿寖鍥?
鍙互浜や粯锛?
- `BACnet/IP UDP`
- 鎸囧畾 `host:port + remoteDeviceInstance`
- 鍙€?`Who-Is / I-Am` 鍙戠幇
- `ReadProperty`
- `ReadPropertyMultiple`
- 鍗曠偣璇?- 鎵归噺璇?- `ReadPropertyMultiple` 澶辫触鑷姩鍥為€€
- 鍩虹 primitive 鍊肩被鍨嬭鍙?
闇€瀹炴満楠岃瘉鍚庡啀瀵瑰璇寸ǔ锛?
- 涓嶅悓鍘傚晢璁惧鐨?`I-Am` 鍏煎鎬?- `objectName / presentValue / units / reliability` 绛夎法鍘傚晢灞炴€у樊寮?- 楂橀杞涓嬬殑鍝嶅簲鏃堕棿鍜屼涪鍖呭蹇嶅害
- 鍗曚釜璁惧瀵?`ReadPropertyMultiple` 鐨勫吋瀹圭▼搴﹀拰鍗曟姤鏂囧睘鎬ф暟涓婇檺

涓嶅缓璁幇鍦烘壙璇猴細

- `WriteProperty`
- `WritePropertyMultiple`
- `COV`
- `BBMD`
- `Foreign Device`
- 璺ㄧ綉娈佃嚜鍔ㄥ彂鐜?- 澶嶆潅鏁扮粍 / sequence / proprietary object

## 鏀寔鐨勫湴鍧€鏍煎紡

褰撳墠瑙ｆ瀽鍣ㄦ敮鎸侊細

1. `analogInput:1.presentValue`
2. `analogValue:12.presentValue`
3. `binaryOutput:3.presentValue`
4. `device:1001.objectName`
5. `analogInput:7.priorityArray[5]`

瑙勫垯锛?
- 鏍囧噯鏍煎紡锛歚<objectType>:<instance>.<property>[<index>]`
- `instance >= 0`
- `[index]` 鍙€?- 鑻ュ湴鍧€涓湭鍐?`[index]`锛屽彲鐢?`additionalConfig.arrayIndex` 鎻愪緵
- `driverDataType` 鍙敱浠ヤ笅瀛楁鎻愮ず锛?  - `additionalConfig.driverDataType`
  - `additionalConfig.bacnetType`
  - `additionalConfig.propertyType`

## 杩炴帴瀛楁

```java
fields.add(createFieldConfig("host", "string", "璁惧IP", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "UDP绔彛", false, "47808", null));
fields.add(createFieldConfig("localBindHost", "string", "鏈湴缁戝畾IP", false, "", null));
fields.add(createFieldConfig("localBindPort", "number", "鏈湴缁戝畾绔彛", false, "", null));
fields.add(createFieldConfig("remoteDeviceInstance", "number", "鐩爣璁惧瀹炰緥鍙?, true, "", null));
fields.add(createFieldConfig("localDeviceInstance", "number", "鏈湴瀹㈡埛绔疄渚嬪彿", false, "", null));
fields.add(createFieldConfig("useWhoIsDiscovery", "boolean", "鍚敤 Who-Is/I-Am 鍙戠幇", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("networkNumber", "number", "BACnet 缃戠粶鍙?, false, "", null));
fields.add(createFieldConfig("macAddress", "string", "杩滅 MAC 鍦板潃", false, "", null));
fields.add(createFieldConfig("covEnabled", "boolean", "鍚敤 COV 璁㈤槄", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("defaultCovLifetimeSeconds", "number", "榛樿 COV 鐢熷懡鍛ㄦ湡(s)", false, "300", null));
fields.add(createFieldConfig("defaultCovIncrement", "number", "榛樿 COV 澧為噺闃堝€?, false, "", null));
fields.add(createFieldConfig("resubscribeOnReconnect", "boolean", "閲嶈繛鍚庤嚜鍔ㄨˉ璁㈤槄", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("apduTimeout", "number", "APDU 瓒呮椂(ms)", false, "5000", null));
fields.add(createFieldConfig("segmentTimeout", "number", "鍒嗘瓒呮椂(ms)", false, "3000", null));
fields.add(createFieldConfig("retries", "number", "閲嶈瘯娆℃暟", false, "1", null));
fields.add(createFieldConfig("maxPropertiesPerRequest", "number", "鍗曟鏈€澶у睘鎬ф暟", false, "32", null));
fields.add(createFieldConfig("readPropertyMultipleEnabled", "boolean", "鍚敤 ReadPropertyMultiple", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("writePropertyMultipleEnabled", "boolean", "鍚敤 WritePropertyMultiple", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("bbmdHost", "string", "BBMD 鍦板潃", false, "", null));
fields.add(createFieldConfig("bbmdPort", "number", "BBMD 绔彛", false, "47808", null));
fields.add(createFieldConfig("foreignDeviceTtlSeconds", "number", "Foreign Device TTL(s)", false, "", null));
fields.add(createFieldConfig("readTimeout", "number", "璇诲彇瓒呮椂(ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "鍗忚瓒呮椂(ms)", false, "5000", null));
```

璇存槑锛?
- 杩欎簺瀛楁骞朵笉浠ｈ〃褰撳墠閮藉凡缁忓疄鐜般€?- 褰撳墠鐪熸宸茬敓鏁堢殑閲嶇偣瀛楁鏄細
  - `host`
  - `port`
  - `localBindHost`
  - `localBindPort`
  - `remoteDeviceInstance`
  - `useWhoIsDiscovery`
  - `maxPropertiesPerRequest`
  - `readPropertyMultipleEnabled`
  - `readTimeout`
  - `timeout`

## 鐐逛綅 AdditionalConfig

```java
fields.add(createFieldConfig("additionalConfig.driverDataType", "string", "椹卞姩鍘熺敓绫诲瀷", false, "AUTO",
        new String[]{"AUTO", "BOOLEAN", "UNSIGNED", "SIGNED", "REAL", "DOUBLE", "ENUM", "STRING", "BIT_STRING"}));
fields.add(createFieldConfig("additionalConfig.arrayIndex", "number", "灞炴€ф暟缁勪笅鏍?, false, "", null));
fields.add(createFieldConfig("additionalConfig.writePriority", "number", "鍐欎紭鍏堢骇", false, "", null));
fields.add(createFieldConfig("additionalConfig.covMode", "string", "COV 妯″紡", false, "OBJECT",
        new String[]{"OBJECT", "PROPERTY"}));
fields.add(createFieldConfig("additionalConfig.covIncrement", "number", "鐐逛綅绾?COV 澧為噺闃堝€?, false, "", null));
```

褰撳墠鐪熸宸茬敓鏁堢殑鏄細

- `additionalConfig.driverDataType`
- `additionalConfig.arrayIndex`

## 娴嬭瘯瑕嗙洊

褰撳墠浠撳簱宸茶鐩栵細

- 鍗忚 alias 鍜?validator 妫€鏌?- 鍗忚 schema 鏆撮湶
- collector 鐘舵€佽緭鍑?- 鍦板潃瑙ｆ瀽
- `ReadProperty` 缂栬В鐮?- `ReadPropertyMultiple` 缂栬В鐮?- 鍋囪澶?`UDP` 闆嗘垚娴嬭瘯
  - `ReadProperty REAL`
  - `ReadProperty STRING`
  - `ReadPropertyMultiple` 鎴愬姛鎵归噺璇?  - `ReadPropertyMultiple Reject -> ReadProperty fallback`
  - `Reject`
  - 瓒呮椂鏂摼
  - `Who-Is / I-Am` 鍙戠幇

## 浠ｇ爜鍏ュ彛

- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetAddress.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/util/BacnetAddressParser.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/*`
- `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/*`
- 鎬讳綋鏂规锛歚../25-BACnet_IP鎺ュ叆鏂规.md`

## BACnet 瀹炵幇琛ラ綈璺嚎鍥?
涓嬮潰鐨勮矾绾垮浘鎸?`P1 / P2 / P3` 鍒囧垎锛岀洰鏍囨槸鎶婂綋鍓?`BACnet/IP P0` 浠庘€滄渶灏忓彲鐢ㄨ疆璇㈣鈥濋€愭琛ラ綈鍒扳€滃彲鐜板満浜や粯鐨?BACnet 鎺ュ叆鑳藉姏鈥濄€?
### P1锛氬悓缃戞鍙氦浠樿兘鍔涜ˉ榻?
鐩爣锛?
1. 鎶婂綋鍓嶅彧鏀寔 `ReadProperty / ReadPropertyMultiple` 鐨勫疄鐜帮紝琛ュ埌鈥滃悓缃戞鍙銆佸彲鍐欍€佸彲璁㈤槄銆佸彲璇婃柇鈥濄€?2. 璁╁綋鍓?schema 閲屽凡缁忔毚闇茬殑鍏抽敭瀛楁锛屽拰杩愯鏃惰兘鍔涘榻愶紝閬垮厤鍓嶇鍙厤浣嗗簳灞備笉鐢熸晥銆?
寤鸿鑼冨洿锛?
1. `WriteProperty`
2. 鍙€?`WritePropertyMultiple`
3. `SubscribeCOV / SubscribeCOVProperty`
4. 鍩虹璇婃柇鍛戒护
5. 閰嶇疆瀛楁鏀跺彛

鍏蜂綋鏀归€犵偣锛?
1. 鍐欏叆閾捐矾
   - 鍦?`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java` 瀹炵幇 `doWritePoint(...)`銆乣doWritePoints(...)`銆?   - 鍦?`src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java` 鏂板 `writeProperty(...)`锛屽悗缁嫢鍚敤鑱氬悎鍐欙紝鍐嶆柊澧?`writePropertyMultiple(...)`銆?   - 鍦?`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/` 鏂板锛歚BacnetWritePropertyRequest`銆乣BacnetWritePropertyResponse`锛涘闇€鑱氬悎鍐欙紝鍐嶈ˉ `BacnetWritePropertyMultipleRequest`銆?   - 鍦?`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/` 鏂板锛歚BacnetWritePropertyCodec`锛涘闇€鑱氬悎鍐欙紝鍐嶈ˉ `BacnetWritePropertyMultipleCodec`锛屽苟瑙?ACK 褰㈠紡琛ュ厖缁熶竴鍝嶅簲瑙ｇ爜銆?   - 鍐欏叆鏃舵帴閫氱偣浣嶆墿灞曞瓧娈碉細`additionalConfig.writePriority`锛涘繀瑕佹椂鎶?`ENUM / BOOLEAN / REAL / UNSIGNED / SIGNED / STRING` 鐨勫弽鍚戠紪鐮佸仛瀹屾暣銆?   - 缁熶竴娌跨敤 `BaseCollector.writePoint/writePoints` 鐨勮川閲忔牎楠屼笌鍙嶅悜杞崲閾捐矾锛屼笉鍗曠嫭鍒嗗弶鍐欏叆娴佺▼銆?
2. 璁㈤槄閾捐矾
   - 鍦?`BacnetIpCollector.java` 瀹炵幇 `doSubscribe(...)`銆乣doUnsubscribe(...)`銆?   - 鍦?`BacnetIpConnectionAdapter.java` 琛ヤ竴涓寔缁帴鏀舵帹閫佹姤鏂囩殑鐩戝惉涓庡垎鍙戞満鍒讹紝涓嶅啀鍙仛鈥滃彂璇锋眰鍚庨樆濉炵瓑鍝嶅簲鈥濄€?   - 鍦?`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/` 鏂板锛歚BacnetSubscribeCovCodec`銆乣BacnetSubscribeCovPropertyCodec`銆乣BacnetCovNotificationDecoder`銆?   - 鏀跺埌鎺ㄩ€佸€煎悗锛岀粺涓€璧?`BaseCollector.ingestPushedValue(...)`锛屼笉瑕佺粫杩囩幇鏈夌紦瀛樸€佷笂鎶ャ€佸疄鏃舵祦閾捐矾銆?   - 灏嗕互涓嬭繛鎺ュ瓧娈电湡姝ｆ帴鍏ヨ繍琛屾椂锛歚covEnabled`銆乣defaultCovLifetimeSeconds`銆乣defaultCovIncrement`銆乣resubscribeOnReconnect`銆乣localBindPort`銆?   - 灏嗕互涓嬬偣浣嶆墿灞曞瓧娈电湡姝ｆ帴鍏ヨ繍琛屾椂锛歚additionalConfig.covMode`銆乣additionalConfig.covIncrement`銆?
3. 鍩虹璇婃柇鍛戒护
   - 鍦?`BacnetIpCollector.java` 瀹炵幇 `doExecuteCommand(...)`锛屽厛琛ユ渶瀹炵敤鐨勪竴缁勫懡浠わ細`who_is`銆乣read_property`銆乣read_property_multiple`銆乣device_info`銆乣discover_objects`銆?   - 涓婂眰鍏ュ彛宸茬粡瀛樺湪锛屽彲鐩存帴娌跨敤锛歚src/main/java/com/wangbin/collector/core/collector/manager/CollectionManager.java`銆乣src/main/java/com/wangbin/collector/api/controller/ControlController.java`銆?   - 鐩爣涓嶆槸鍋氬叏鍔熻兘璋冭瘯鍙帮紝鑰屾槸鍏堝叿澶団€滅幇鍦烘帓闅滀笉蹇呮敼浠ｇ爜鈥濈殑鏈€灏忚兘鍔涖€?
4. 閰嶇疆瀛楁鏀跺彛
   - 鍦?`src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java` 鏍囨竻鍝簺瀛楁宸茬敓鏁堛€佸摢浜涘瓧娈典粛鏄鐣欍€?   - 鍦?`src/main/java/com/wangbin/collector/core/config/validator/ProtocolConnectionValidator.java` 澧炲姞鈥滃姛鑳藉紑鍚嵆瑕佹眰閰嶅瀛楁榻愬叏鈥濈殑鏍￠獙锛屼緥濡傦細`covEnabled=true` 鏃舵牎楠屾湰鍦扮洃鍚弬鏁帮紝`writePropertyMultipleEnabled=true` 鏃舵牎楠岃澶囩鍏煎绛栫暐涓庢壒閲忓ぇ灏忋€?   - 濡傛灉鏌愪簺瀛楁鍦?P1 浠嶄笉鎵撶畻鏀寔锛屽簲鍦ㄥ崗璁枃妗ｅ拰 schema 鎻忚堪閲屾槑纭啓鎴?`reserved / not active yet`銆?
5. P1 寤鸿鏂板娴嬭瘯
   - `WriteProperty` 闆嗘垚娴嬭瘯
   - `SubscribeCOV` 鎺ㄩ€佹帴鏀舵祴璇?   - 閲嶈繛鍚庤嚜鍔ㄨˉ璁㈤槄娴嬭瘯
   - `executeCommand` 鍛戒护闆嗘垚娴嬭瘯
   - 鍐欏叆澶辫触銆佹嫆缁濄€佽秴鏃躲€佸彇娑堣闃呯殑寮傚父璺緞娴嬭瘯

P1 瀹屾垚鏍囧噯锛?
1. 鍚岀綉娈?BACnet/IP 璁惧鍙ǔ瀹氬畬鎴愯銆佸啓銆丆OV 璁㈤槄銆?2. 鏂囨。銆乻chema銆乿alidator銆佽繍琛屾椂鐢熸晥瀛楁淇濇寔涓€鑷淬€?3. 鐜板満鏈€甯歌鐨勨€滄敼鍊笺€佽闃呫€佽瘖鏂€濅笉鍐嶄緷璧栦复鏃舵敼浠ｇ爜銆?
### P2锛氱綉缁滃吋瀹规€т笌澶嶆潅瀵硅薄鑳藉姏琛ラ綈

鐩爣锛?
1. 瑙ｅ喅璺ㄥ瓙缃戙€佸鏉傚睘鎬с€佸ぇ鎶ユ枃銆佸巶鍟嗗樊寮傚鑷寸殑鐜板満鍏煎鎬ч棶棰樸€?2. 鎶娾€滆兘鎺ュ皯閲忕畝鍗曠偣浣嶁€濇彁鍗囧埌鈥滆兘鎺ユゼ鎺ч」鐩噷鐨勫吀鍨嬪鏉傚璞♀€濄€?
寤鸿鑼冨洿锛?
1. `BBMD / Foreign Device Registration`
2. 璺敱涓庤法瀛愮綉鍙戠幇
3. 鍒嗘鎶ユ枃
4. constructed / array / sequence 瑙ｇ爜
5. 绉佹湁瀵硅薄 / 绉佹湁灞炴€ц闂?
鍏蜂綋鏀归€犵偣锛?
1. 璺ㄥ瓙缃戜笌璺敱
   - 鍦?`BacnetIpConnectionAdapter.java` 鎺ュ叆锛歚bbmdHost`銆乣bbmdPort`銆乣foreignDeviceTtlSeconds`銆乣networkNumber`銆乣macAddress`銆?   - 鏂板 BBMD/FD 娉ㄥ唽銆佺画绉熴€佸け鏁堥噸璇曢€昏緫銆?   - `Who-Is / I-Am` 涓嶅啀鍙仛鈥滄寜瀹炰緥鍙锋壘鍗曡澶団€濓紝琛ュ箍鎾彂鐜般€佹寚瀹?network 鑼冨洿鍙戠幇銆佽矾鐢辩浉鍏宠瘖鏂懡浠ゃ€?
2. 瓒呮椂銆侀噸璇曘€佷細璇濆弬鏁?   - 鍦?`BacnetIpUdpClient.java` 鍜?`BacnetIpConnectionAdapter.java` 鐪熸鎺ュ叆锛歚apduTimeout`銆乣segmentTimeout`銆乣retries`銆?   - 褰撳墠 `invokeId` 鍙槸绠€鍗曡嚜澧烇紝P2 搴旇ˉ鏇寸ǔ鍋ョ殑璇锋眰涓婁笅鏂囩鐞嗭紝閬垮厤骞跺彂銆侀噸璇曘€佽繜鍒板搷搴旂浉浜掓薄鏌撱€?
3. 鍒嗘鍝嶅簲鏀寔
   - 鍦?`BacnetReadPropertyResponseDecoder.java`銆乣BacnetReadPropertyMultipleResponseDecoder.java` 琛ュ垎娈?`ComplexACK` 閲嶇粍銆?   - `BacnetIpUdpClient.java` 闇€瑕佷粠鈥滀竴鍙戜竴鏀垛€濆崌绾т负鈥滃悓涓€璇锋眰涓婁笅鏂囦笅鐨勫娈垫敹鍖呬笌缁勮鈥濄€?   - 娌℃湁杩欎竴姝ワ紝澶у璞°€侀暱灞炴€у垪琛ㄣ€佸鏉傚璞¤鍙栦粛浼氬ぇ閲忓け璐ャ€?
4. 澶嶆潅绫诲瀷瑙ｇ爜
   - 褰撳墠 `ANY` 鍙敮鎸?primitive锛孭2 搴斿湪 `codec/` 涓嬭ˉ閫氱敤鍊兼ā鍨嬪拰閫掑綊瑙ｇ爜鍣ㄣ€?   - 寤鸿鏂板锛歚BacnetAnyValue`銆乣BacnetConstructedValue`銆乣BacnetArrayValue`銆乣BacnetSequenceValue`銆?   - 鍏堜紭鍏堟墦閫氳繖浜涚幇鍦洪珮棰戝睘鎬э細`objectList`銆乣priorityArray`銆乣stateText`銆乣statusFlags`銆乣reliability`銆乣units`銆?   - 闇€瑕佸悓姝ヨ瘎浼?`ProcessResult` 閲屽浣曟壙杞界粨鏋勫寲鍊硷紱鑻ユ渶缁堜粛鎸夋爣閲忎笂鎶ワ紝搴旀彁渚涘彲閫夋墎骞冲寲绛栫暐銆?
5. 绉佹湁瀵硅薄 / 绉佹湁灞炴€?   - 褰撳墠 `BacnetObjectType.java`銆乣BacnetPropertyIdentifier.java` 瀵规湭鐭?id 浼氱洿鎺ユ姤閿欍€?   - P2 搴斿厑璁糕€滄爣鍑嗘灇涓句紭鍏堬紝鏈煡鏁板€奸€忎紶鈥濓紝鍚﹀垯鍘傚晢绉佹湁瀵硅薄寰堥毦鎺ャ€?   - `BacnetAddressParser.java` 涔熻鎵╁睍鍦板潃璇硶锛岃嚦灏戞敮鎸佹暟鍊煎瀷瀵硅薄绫诲瀷鍜屽睘鎬?id锛岃€屼笉鍙緷璧栧綋鍓嶅唴缃灇涓惧悕銆?
6. P2 寤鸿鏂板娴嬭瘯
   - BBMD / Foreign Device 娉ㄥ唽涓庣画绉熸祴璇?   - 鍒嗘 `ReadProperty` / `ReadPropertyMultiple` 闆嗘垚娴嬭瘯
   - `priorityArray`銆乣objectList`銆乣stateText` 瑙ｇ爜娴嬭瘯
   - 绉佹湁瀵硅薄 / 绉佹湁灞炴€у湴鍧€瑙ｆ瀽涓庤鍙栨祴璇?   - 璺ㄥ瓙缃戝彂鐜颁笌璺敱璇婃柇娴嬭瘯

P2 瀹屾垚鏍囧噯锛?
1. BACnet/IP 涓嶅啀灞€闄愪簬鍚屽瓙缃戠畝鍗曡鐐广€?2. 瀵瑰吀鍨嬫ゼ鎺ч」鐩腑鐨勫鏉傚睘鎬у拰澶у搷搴斿叿澶囩ǔ瀹氬吋瀹规€с€?3. 鍘傚晢绉佹湁鐐硅〃涓嶅啀鍥犱负鏋氫妇琛ㄧ己澶辫€岀洿鎺ヤ笉鍙帴鍏ャ€?
### P3锛氬钩鍙扮骇 BACnet 鑳藉姏寤鸿

鐩爣锛?
1. 浠庘€滃崗璁┍鍔ㄢ€濆崌绾у埌鈥滃钩鍙扮骇 BACnet 瀛愮郴缁熲€濄€?2. 瑙ｅ喅澶ц妯¤澶囨帴鍏ャ€佸璞″缓妯°€佸巻鍙插璞°€佸憡璀︿笌浜掓搷浣滈獙璇侀棶棰樸€?
寤鸿鑼冨洿锛?
1. 浼犺緭灞傛墿灞?2. 瀵硅薄鍙戠幇涓庡缓妯?3. 鍘嗗彶涓庝簨浠惰兘鍔?4. 浜掓搷浣滀笌瑙傛祴浣撶郴

鍏蜂綋鏀归€犵偣锛?
1. 浼犺緭灞傛墿灞?   - 璇勪及鏄惁缁х画瀹屽叏鑷爺锛岃繕鏄紩鍏ユ垚鐔熷簱鎵挎帴鏇村鏉?transport銆?   - 鑻ョ户缁嚜鐮旓紝寤鸿鎶?`protocol/bacnet` 鍐嶆媶灞傦細`transport/`銆乣session/`銆乣service/`銆乣model/`銆?   - P3 鍙瘎浼版敮鎸侊細`IPv6`銆乣MS/TP`銆乣BACnet/SC`銆?   - 杩欎竴闃舵涓嶅缓璁户缁妸鎵€鏈夊崗璁涔夐兘鍫嗗湪 `BacnetIpCollector` 涓€涓被閲屻€?
2. 瀵硅薄鍙戠幇涓庡缓妯?   - 鏂板鈥滆澶囧揩鐓?/ 瀵硅薄鐩綍 / 灞炴€х紦瀛樷€濊兘鍔涖€?   - 閫氳繃鍛戒护鎴栧悗鍙颁换鍔″缓绔嬶細device 鍩烘湰淇℃伅銆乷bject list銆佸父鐢ㄥ睘鎬у揩鐓с€?   - 渚夸簬鍓嶇鐐逛綅杈呭姪閰嶇疆銆佸樊寮傛瘮瀵广€佽澶囧贰妫€鍜岃嚜鍔ㄧ敓鎴愬€欓€夌偣琛ㄣ€?   - 鐩稿叧鑳藉姏寤鸿娌夊埌鍗曠嫭鏈嶅姟锛岃€屼笉鏄户缁杩?collector 涓昏閾捐矾銆?
3. 鍘嗗彶涓庝簨浠惰兘鍔?   - 琛?`ReadRange`銆乣TrendLog`銆佷簨浠?鍛婅瀵硅薄璇诲彇銆?   - 璇勪及鏄惁闇€瑕侊細alarm acknowledge銆乪vent enrollment / notification class 鐩稿叧璇婃柇銆乼ime sync / schedule 鐩稿叧鍛戒护銆?   - 杩欎竴闃舵鎵嶉€傚悎鎶?BACnet 浠庘€滈噰闆嗗崗璁€濇墿灞曟垚鈥滄ゼ鎺х郴缁熸帴鍏ュ崗璁€濄€?
4. 瑙傛祴涓庝簰鎿嶄綔
   - 鍦ㄧ洃鎺ч噷鏂板 BACnet 涓撻」鎸囨爣锛歚rpmFallbackCount`銆乣covNotificationCount`銆乣covResubscribeFailureCount`銆乣segmentedResponseCount`銆乣bbmdRenewFailureCount`銆乣invokeIdMismatchCount`銆?   - 鏋勫缓鍘傚晢鍏煎鐭╅樀锛岃嚦灏戣鐩栵細Siemens銆丣ohnson Controls銆丠oneywell銆乀rane銆丏elta銆佸浗浜у父瑙佹ゼ鎺х綉鍏炽€?   - 鑻ュ悗缁瀵瑰鎵胯鈥滃ぇ瑙勬ā BACnet 浜や粯鈥濓紝搴斿紩鍏ユ洿鎺ヨ繎 BTL 鍦烘櫙鐨勫洖褰掓祴璇曢泦銆?
5. P3 寤鸿鏂板娴嬭瘯
   - 澶氳澶囧苟鍙戜笌澶х偣琛ㄥ帇娴?   - 澶嶆潅瀵硅薄鍏ㄩ噺鎵弿鍥炲綊
   - 鍘嗗彶瀵硅薄 / TrendLog 璇诲彇娴嬭瘯
   - 澶氬巶鍟嗕簰鎿嶄綔娴嬭瘯
   - 闀跨ǔ娴嬭瘯锛氶噸杩炪€佺画绉熴€佸箍鎾鏆淬€佸垎娈垫姤鏂囥€佽繜鍒板搷搴?
P3 瀹屾垚鏍囧噯锛?
1. BACnet 鑳藉姏涓嶅啀鍙槸鈥滃崗璁彃浠垛€濓紝鑰屾槸鐙珛鍙紨杩涚殑瀛愮郴缁熴€?2. 鏀寔澶ц妯￠」鐩殑鍙戠幇銆佸缓妯°€侀噰闆嗐€佸啓鍏ャ€佽闃呫€佽瘖鏂拰瑙傛祴銆?3. 鑳藉鏇寸ǔ濡ュ湴瀵瑰鎵胯鈥淏ACnet 鐜板満浜や粯鑳藉姏鈥濄€?
### 闃舵鎺掑簭寤鸿

寤鸿涓ユ牸鎸変笅闈㈤『搴忔帹杩涳細

1. `P1`锛氬厛琛ュ啓鍏ャ€丆OV銆佽瘖鏂€佸瓧娈垫敹鍙ｏ紝鎶婂悓缃戞鐜板満鏈€甯歌闇€姹傛墦閫氥€?2. `P2`锛氬啀琛?BBMD銆佸垎娈点€佸鏉傚睘鎬с€佺鏈夊璞★紝瑙ｅ喅鍏煎鎬у拰璺ㄧ綉娈甸棶棰樸€?3. `P3`锛氭渶鍚庡啀鍋?BACnet/SC銆丮S/TP銆乀rendLog銆佸璞″缓妯°€佸吋瀹圭煩闃电瓑骞冲彴绾у缓璁俱€?
濡傛灉璧勬簮鏈夐檺锛屾渶灏忛棴鐜簲鑷冲皯瀹屾垚锛?
1. `WriteProperty`
2. `SubscribeCOV`
3. `BBMD / Foreign Device`
4. 鍒嗘鍝嶅簲鏀寔
5. `priorityArray / objectList` 杩欑被澶嶆潅灞炴€цВ鐮?
## 当前进展补充（2026-06-30）

### 已落地：BACnet MS/TP 传输层

1. 传输帧与 CRC 已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpFrame.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpFrameCodec.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpCrc.java`
2. token passing / poll-for-master 已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/transport/BacnetMstpTokenManager.java`
3. 串口收发抽象与默认实现已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/transport/BacnetSerialChannel.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/transport/JSerialCommBacnetSerialChannel.java`
   - `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetMstpConnectionAdapter.java`
4. 采集器与工厂接入已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetMstpCollector.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetMstpClient.java`
   - `src/main/java/com/wangbin/collector/core/connection/factory/ConnectionFactory.java`
   - `src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java`
   - `src/main/java/com/wangbin/collector/core/config/validator/ProtocolConnectionValidator.java`
5. 当前支持范围：
   - 面向采集场景的主站侧 token 接收、空闲 claim、Poll For Master、Reply To Poll、Token 传递。
   - 复用现有 BACnet APDU/NPDU 编解码能力，已打通 `ReadProperty` 读点闭环。
   - 支持 `serialPort`、`baudRate`、`dataBits`、`stopBits`、`parity`、`localMacAddress`、`remoteMacAddress`、`maxMaster`、`maxInfoFrames` 等关键连接参数。
6. 当前限制：
   - 当前是为采集框架服务的精简 MS/TP master 实现，不是完整的 BACnet MS/TP 状态机与互操作认证实现。
   - 暂未覆盖从站代理、复杂多主竞争调优、链路层诊断对象、MS/TP 路由器场景。
   - 当前测试以内存串口通道模拟为主，真实串口兼容性仍需现场设备回归。

### 已落地：BACnet/SC（实验性）

1. 代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetScClient.java`
   - `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetScConnectionAdapter.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetScCollector.java`
2. 当前实现方式：
   - 使用 secure WebSocket 二进制通道承载 BACnet 请求/响应报文。
   - 复用现有 `BacnetIpCollector` 的读点、写点、订阅、分段响应重组、未知对象/私有属性动态透传能力。
3. 当前接入字段：
   - `url`、`host`、`port`、`path`、`subprotocol`、`remoteDeviceInstance`、`timeout`、`connectTimeout`、`apduTimeout`、`segmentTimeout`、`retries`。
4. 当前限制：
   - 当前定位是实验性 secure tunnel 接入，并未完整实现标准 BACnet/SC hub / node 会话模型。
   - 证书信任链治理、节点发现、邻居/路由分发、标准化连接管理仍需后续继续补齐。
   - 在项目对外宣称 BACnet/SC 交付能力之前，必须先做目标平台互操作回归。

### 本轮新增测试

1. `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpFrameCodecTest.java`
2. `src/test/java/com/wangbin/collector/core/connection/adapter/BacnetMstpConnectionAdapterTest.java`
3. `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetMstpCollectorTest.java`
4. `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetScCollectorTest.java`

以上补齐后，BACnet 这条线已经从“仅 BACnet/IP 基础读点”推进到“BACnet/IP 增强 + MS/TP 传输闭环 + BACnet/SC 实验接入”的状态，但 BACnet/SC 标准化互操作与 MS/TP 大规模现场验证仍应继续放在后续阶段推进。

## 待完成与待优化清单（2026-06-30 核对追加）

说明：

- 状态约定：`[ ]` 未完成，`[~]` 部分完成，`[x]` 已完成。
- 后续完成某项时，直接修改对应状态，并在条目后补充完成日期、代码入口、测试入口。
- 本区仅记录“当前代码已确认仍未闭环”或“架构上建议继续补齐”的事项。

### A. 功能未完全实现

- `[x]` `WritePropertyMultiple`
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetWritePropertyMultipleRequest.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetWritePropertyMultipleCodec.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`、`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetReadPropertyCodecTest.java`
  - 说明：已补齐 `domain + codec + client + connection adapter + collector`，支持 `writePropertyMultipleEnabled=true` 时聚合写，失败后自动逐点回退 `WriteProperty`。

- `[x]` confirmed `COV Notification` 接收闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetCovNotificationDecoder.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetConfirmedCovNotificationCodec.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetIpUdpClient.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已补齐 confirmed COV 解码、SimpleACK 确认和 collector 推送入链。
  - 当前情况：当前入站仅实现 unconfirmed `COV Notification` 解码；订阅请求虽然可携带 `issueConfirmedNotifications=true`，但服务端若真的回 confirmed COV，当前没有对应接收与确认处理。
  - 完成标记建议：补 confirmed COV APDU 解码、ACK/处理链路、超时/重发策略、模拟服务端测试。

- `[x]` `resubscribeOnReconnect` 重连后自动恢复订阅
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`、`src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已补齐适配器重连回调、collector 自动补订阅与失败计数。
  - 当前情况：配置字段已暴露，但当前 `BacnetIpCollector` / 调度层未实现“连接失效后重连并恢复 COV 订阅”闭环。
  - 完成标记建议：补连接恢复后的订阅重建逻辑，并覆盖断线、重连、重复订阅去重、失败重试测试。

- `[x]` `defaultCovIncrement` 连接级默认增量阈值生效
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：属性级 COV 订阅已支持点位 `covIncrement` 优先、连接级 `defaultCovIncrement` 回退。

- `[x]` `covEnabled` 配置到订阅行为的统一闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`、`src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`、`src/test/java/com/wangbin/collector/core/collector/scheduler/CollectionSchedulerTest.java`
  - 说明：`covEnabled` 已统一控制自动订阅、轮询绕过和推送点调度语义。

- `[x]` constructed / array / sequence / complex `ANY` 通用解码
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetValueDecoder.java`、`BacnetReadPropertyResponseDecoder.java`、`BacnetReadPropertyMultipleResponseDecoder.java`、`BacnetCovNotificationDecoder.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetReadPropertyCodecTest.java`
  - 说明：已补齐 BACnet application primitive、constructed、array/sequence 统一解码路径，并覆盖 `objectList`、`priorityArray`、`statusFlags` 等典型复杂属性。
  - 当前情况：当前读值解码以 primitive 为主，支持 `NULL / BOOLEAN / UNSIGNED / SIGNED / REAL / DOUBLE / CHARACTER_STRING / BIT_STRING / ENUMERATED / OBJECT_IDENTIFIER`，支持 `arrayIndex` 定位，但不支持通用 constructed/sequence/复杂数组属性展开。
  - 直接影响：`priorityArray`、复杂对象属性、厂商扩展 constructed 值、嵌套 sequence 返回仍不能稳定接入。
  - 完成标记建议：补通用 tag walker、constructed value model、序列化策略，并补真实复杂属性模拟测试。

- `[x]` 复杂 BACnet 属性的结果建模策略
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetValue.java`、`BacnetValueKind.java`、`BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已定义统一 `BacnetValue`/`BacnetValueKind` 建模，并在 `ProcessResult.metadata` 中固化 `bacnetValueType`、`bacnetComplexValue`、`bacnetValueMetadata`，复杂值按统一 passthrough 模式进入缓存/告警/上报链。
  - 当前情况：当前 `ProcessResult` 路径对 scalar 友好，但对复杂数组/对象结果缺统一约束，尚未定义“原样透传 JSON / typed model / flattened map”的平台标准。
  - 完成标记建议：先定平台层结果模型，再补复杂属性读链路，避免后续接口反复变更。

- `[ ]` `BACnet/SC` 标准 hub / node 会话模型
  - 当前情况：当前是 secure WebSocket binary tunnel 实验接入，不是完整标准 `BACnet/SC` 实现。
  - 完成标记建议：补标准会话治理、证书/信任链、节点发现、邻居/路由控制，并做目标平台互操作回归。

- `[ ]` `MS/TP` 大规模现场兼容性验证
  - 当前情况：当前 `MS/TP` 已有 transport/read 闭环与内存串口模拟测试，但真实串口、多主竞争、复杂现场兼容性仍未完成。
  - 完成标记建议：补真实串口回归、不同波特率/校验位/多主设备验证和长期稳定性测试。

### B. 流程与框架接入待补充

- `[x]` 调度层自动订阅 BACnet 订阅点的策略
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/scheduler/CollectionSchedulerTest.java`
  - 说明：调度器启动阶段已自动识别 `SUBSCRIPTION/EVENT` 点并调用订阅，同时从轮询计划剔除。

- `[x]` BACnet 推送链路与轮询链路的统一配置语义
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`、`src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`、`src/test/java/com/wangbin/collector/core/collector/scheduler/CollectionSchedulerTest.java`
  - 说明：已明确 `covEnabled + collectionMode=SUBSCRIPTION/EVENT` 的推送点模型，并统一到轮询绕过和调度编排。

- `[x]` BACnet 专项监控指标的系统化暴露
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已在 `getDeviceStatus()/protocolMetrics` 暴露 COV、重连、分段、Foreign Device、fallback 等 BACnet 专项指标。

### C. 架构优化与代码组织建议

- `[x]` 拆分 `BacnetIpCollector`
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/service/BacnetValueMapper.java`、`BacnetSubscriptionService.java`、`BacnetDeviceSnapshotService.java`、`BacnetWriteRequestBuilder.java`
  - 说明：已将复杂值映射、订阅构造与匹配、设备快照、写请求构造从 collector 中拆出，`BacnetIpCollector` 主类收敛为连接调度、读写编排和统一框架接入职责。

- `[ ]` 拆分 BACnet 协议栈层次
  - 当前情况：已经有 `client / codec / domain / transport` 雏形，但还缺更清晰的 `session / service / model` 层次边界。
  - 建议方向：逐步把 `invokeId`、分段、ACK、confirmed/unconfirmed service、对象/属性模型从 collector 侧继续下沉。

- `[x]` 建立复杂类型独立解码层
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetValueDecoder.java`
  - 说明：已建立独立 BACnet value decoder，`ReadProperty / ReadPropertyMultiple / COV` 统一复用，处理 primitive、constructed、array、sequence 和复杂 `ANY` 值。

- `[x]` 建立 BACnet 设备快照 / 对象目录 / 属性缓存服务
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/service/BacnetDeviceSnapshotService.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetDeviceSnapshot.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java`
  - 说明：已抽出设备快照、对象目录和属性缓存服务，`device_info` / `discover_objects` 命令已切换到该服务。

- `[x]` 明确复杂值在缓存/上报/实时流中的标准表示
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/report/shadow/ShadowManager.java`、`src/main/java/com/wangbin/collector/core/report/model/ReportData.java`、`src/main/java/com/wangbin/collector/core/report/service/IoTProtocolService.java`、`src/main/java/com/wangbin/collector/core/report/adapter/JsonProtocolAdapter.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/report/shadow/ShadowManagerTest.java`、`src/test/java/com/wangbin/collector/core/report/service/CacheReportServiceTest.java`
  - 说明：已统一复杂 BACnet 值通过 `ProcessResult.metadata` 固化为 `bacnetValueType`、`bacnetComplexValue`、`bacnetValueMetadata`，并贯通影子、上报、协议消息和实时流。

### D. 回归测试与交付验证待补充

- `[x]` `WritePropertyMultiple` 集成测试
  - 完成日期：`2026-06-30`
  - 代码入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/FeatureBacnetTestServer.java`
  - 测试入口：`mvn "-Dtest=BacnetReadPropertyCodecTest,BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest" test`
  - 说明：已覆盖 WPM 编码、聚合写成功、Reject 后逐点 fallback 与 BACnet 主链回归。
- `[x]` confirmed `COV Notification` 集成测试
  - 完成日期：`2026-06-30`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java#shouldHandleConfirmedCovNotificationInIntegrationPath`
  - 说明：已覆盖 confirmed COV 入站通知、collector 接收处理与 ACK 回包集成闭环。
- `[x]` 断线重连 + 自动恢复订阅集成测试
  - 完成日期：`2026-06-30`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java#shouldRecoverConnectionAndResubscribeAfterTimeoutWhenCovEnabled`
  - 说明：已覆盖读超时导致连接失效、自动恢复连接、恢复订阅并继续读取的集成路径。
- `[x]` 复杂数组 / sequence / `priorityArray` / `objectList` 解码测试
  - 完成日期：`2026-06-30`
  - 代码入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetReadPropertyCodecTest.java`、`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已补 decoder 级和 collector 级回归，验证复杂属性读值、复杂值透传与元数据保留。

### 本轮完成记录（2026-06-30）

- `[x]` 第一批：复杂值解码与结果建模闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetValueDecoder`、`BacnetIpCollector`、`BacnetReadPropertyResponseDecoder`、`BacnetReadPropertyMultipleResponseDecoder`、`BacnetCovNotificationDecoder`
  - 测试入口：`mvn "-Dtest=BacnetReadPropertyCodecTest,BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest" test`
  - 说明：已完成从 BACnet 读值/COV 值解码到 `ProcessResult`、缓存/上报链的复杂值闭环，后续继续推进 COV 自动订阅/重连恢复和 `WritePropertyMultiple`。
- `[x]` 第二批：COV 自动订阅、confirmed 通知与重连恢复闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetIpCollector`、`CollectionScheduler`、`BacnetConnectionAdapter`、`BacnetIpConnectionAdapter`、`BacnetScConnectionAdapter`、`BacnetMstpConnectionAdapter`、`BacnetCovNotificationDecoder`、`BacnetConfirmedCovNotificationCodec`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorFeatureTest,CollectionSchedulerTest" test`
  - 说明：已完成 `covEnabled`/`defaultCovIncrement`/`resubscribeOnReconnect` 运行时闭环，confirmed COV ACK、自动订阅、轮询绕过与 BACnet 专项指标一并落地。
- `[x]` 第三批：`WritePropertyMultiple` 批量写闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetWritePropertyMultipleRequest`、`BacnetWritePropertyMultipleCodec`、`BacnetIpCollector`、`BacnetIpUdpClient`、`BacnetIpConnectionAdapter`、`BacnetScClient`、`BacnetMstpClient`
  - 测试入口：`mvn "-Dtest=BacnetReadPropertyCodecTest,BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest" test`
  - 说明：已完成 BACnet 聚合写请求编码、三种传输适配、collector 写聚合策略与失败自动回退。
- `[x]` 第四批：设备快照 / 对象目录 / 属性缓存服务
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetDeviceSnapshotService`、`BacnetDeviceSnapshot`、`BacnetIpCollector`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorIntegrationTest#shouldBuildDeviceSnapshotThroughDeviceInfoAndDiscoverObjectsCommands" test`
  - 说明：已将 `device_info`、`discover_objects`、属性缓存从 collector 内部流程中抽出为独立服务。
- `[x]` 第五批：collector 架构拆分、复杂值链路标准化与 COV 集成回归补齐
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetValueMapper`、`BacnetSubscriptionService`、`ShadowManager`、`ReportData`、`IoTProtocolService`、`JsonProtocolAdapter`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorIntegrationTest,BacnetIpCollectorFeatureTest,BacnetReadPropertyCodecTest,ShadowManagerTest,CacheReportServiceTest,TelemetryStreamServiceImplTest,CollectorDataPostProcessorTest" test`
  - 说明：已完成 `BacnetIpCollector` 进一步拆分、复杂值在缓存/影子/上报/实时流中的统一表示，并补齐 confirmed COV 与断线恢复订阅集成测试。
- `[ ]` 跨子网 `BBMD / Foreign Device` 长稳测试
- `[ ]` 多设备并发、大点表、长时间运行稳定性测试
- `[ ]` 多厂商兼容矩阵回归测试

### E. 完成记录模板

后续某项完成时，建议按下面格式直接更新对应条目：

- `[x]` 条目名称
  - 完成日期：`YYYY-MM-DD`
  - 代码入口：`类 / 方法 / 文件`
  - 测试入口：`测试类 / 用例`
  - 说明：一句话说明完成范围与边界
