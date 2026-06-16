package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 濠电姴鐥夐弶搴撳亾閺囥垹纾圭痪顓炴噺閹冲矂姊绘笟鈧埀顒傚仜閼活垶宕濋鐐寸厱闁斥晛鍠氬▓鏃€顨ラ悙宸剶婵﹦绮幏鍛存偡閹殿喚銈锋繝鐢靛仜濡﹪宕㈡總绋跨厺闁哄啫鐗婇弲婊堟煟閹伴潧澧い蹇婃櫊濮婃椽宕ㄦ繝鍌氼潎闂佸憡鏌ㄩ惌鍌炪€佸▎鎰瘈闁搞儯鍔庨崢?- 闂傚倸鍊峰ù鍥Υ閳ь剟鏌涚€ｎ偅灏伴柕鍥у瀵粙濡歌濡插牓姊?000闂?缂傚倸鍊搁崐椋庣矆娓氣偓钘濋柟娈垮枟閺嗘粓鏌ｉ幇顓犮偞闁哄妫冮弻鐔衡偓鐢殿焾閸撹鲸绻涢崼鐕佹畷闁逛究鍔岄—鍐嫚闊厼顥氬┑锛勫亼閸娧呭緤娴犲鍋夊┑鍌滎焾閽冪喓鈧厜鍋撻柛鏇ㄥ亞閸婄偛顪冮妶鍡楃瑐婵炴潙娲畷?
 * 闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰电厑濞差亜惟闁宠桨鐒﹂悗顒勬⒑闂堟稓澧曟い锔诲灣閻氭儳顓兼径瀣幈濡炪倖鍔х徊鍓х矆閸愵喗鐓ユ繛鎴炶壘閺嬫盯鏌″畝瀣埌閾绘牠鏌嶈閸撴瑧鍙呴梺鍐叉惈閹冲繘宕曞澶嬬厽闁哄倹瀵ч崯鐐烘煕?+ 闂備浇顕х€涒晠顢欓弽顓炵獥闁哄稁鍘肩壕鍦喐閻楀牆淇柡浣告閺岋綁骞囬锝嗏挅闁荤喐鐟ョ€氼厽鍒婇幘顔藉仭婵炲棗绻愰鈺呮煟閿曚礁鍚圭紒?+ 闂傚倸鍊风粈渚€骞栭锕€绠犳俊顖濆亹绾捐姤鎱ㄥΟ鎸庣【缂佲偓閸屾稒鍙忔俊鐐额嚙娴滄儳螖閻橀潧浠滅紒澶屽厴婵＄敻骞囬弶璺唺闂佺懓顕崕鎰椤撱垺鈷掑ù锝呮贡濠€浠嬫煕閺傝法鐏遍柍褜鍓氶崙瑙勭閻愬弬锝夊箛闁附鏅ｉ梺闈涚箳婵兘藝闁秵鈷戦柛婵嗗琚梺鍛婃煥鐎涒晝绮嬮幒妤€绠瑰ù锝呮贡閸?
 */
@Slf4j
@Service
public class CollectionScheduler {

    @Autowired
    private CollectionManager collectionManager;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private CollectionStatistics collectionStatistics;

    @Autowired
    private CollectorProperties collectorProperties;

    @Autowired
    private CollectionServiceHealthTracker collectionServiceHealthTracker;

    @Autowired
    private DeviceBatchPlanner deviceBatchPlanner;

    @Autowired
    private CollectedDataProcessor collectedDataProcessor;

    // ================== 濠电姷鏁搁崑鐐差焽濞嗘挸瑙﹂悗锝庡枟閺咁亪姊虹拠鏌ヮ€楅柣蹇斿哺閹繝鏁撻悩鑼暰闂佸憡鍔﹂崰鏍矆鐎ｎ偁浜滈柟鎹愭硾娴狅箓鏌ｉ妶鍛窛缂佽鲸鎸婚幏鍛村礈閹绘帒澹堟俊鐐€栭崹闈浳涘┑瀣畾闁割偅绻嶉悡銉╂煕椤愩倕鏋旈柛妯诲姍閹鐛崹顔煎濠电偛鐪伴崝鎴濐嚕?==================

    // 1. 闂傚倸鍊风粈渚€骞栭锕€鐤い鎰堕檮閸嬪鏌ｉ幘鍐差唫婵炴垯鍨归柨銈嗕繆閵堝嫮顦﹀ù婊堜憾濮婃椽骞栭悙鎻掑Ф婵炲瓨绮庨崑娑㈠煝閹捐閱囬柡鍥╁仧椤旀劙鏌℃径濠勫濠⒀傜矙閹偓娼忛妸褏顔?- 闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶銉ョ仾闁稿孩顨嗘穱濠囧Χ閸涱厽娈滈梺绋款儐閹瑰洭骞冮悜钘夌妞ゆ梻铏庨崯鍫熶繆閻愵亜鈧倝宕戦崟顐€娲Ω閳轰胶顔嗗銈嗗姧缁犳垹绮堥崘顔界厪濠电偛鐏濋崝鎾煕?
    private final ScheduledExecutorService timeSliceScheduler;

    // 2. 闂傚倸鍊风粈浣虹礊婵犲偆鐒界憸蹇曟閻愬绡€闁搞儜鍥紬闂備胶绮崹鍫曗€﹂崶顑锯偓鍛存倻閼恒儱浠梺鎼炲劘閸斿瞼寰婄紒妯镐簻妞ゆ劗濮撮埀顒佺箞瀵鈽夐姀鐘殿唺闂佺懓顕崕鎰涢悙鐑樺仭婵犲﹤瀚欢鏌ユ煕婵犲倻浠㈤柣?- 闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶銉ョ仾闁稿孩顨嗘穱濠囧Χ閸曨喖鍘″銈傛櫇閸忔﹢骞冭ぐ鎺戠倞闁靛鍎崇粊宄邦渻閵堝骸浜栭柛濠冪箞瀵鈽夐姀鐘殿唺闂佺懓顕崕鎰涢悙鐑樺仭?
    private final ExecutorService batchDispatcher;

    // 3. 闂備浇顕х€涒晠顢欓弽顓炵獥闁哄稁鍘肩壕鍦喐閻楀牆淇柡浣告閺岋綁骞囬鐓庡闂佺粯鎸搁崐鍧楀蓟濞戙垹唯妞ゆ梻鍘ч～顏堟⒑閼规澘鐨哄┑鐐╁亾闂佸搫鐬奸崰鎾跺垝椤撶偐妲堟俊顖欑串缁辩偤姊虹悰鈥充壕婵炲濮撮鍡涙偂?- 闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶銉ョ仾闁稿孩顨嗘穱濠囧Χ閸涱厽娈滈梺绋款儐閹瑰洤鐣烽悜绛嬫晣闁绘劗澧楅～鏇熺節绾板纾块柧蹇撻叄瀹曞綊宕奸弴鐐舵憰閻庡厜鍋撻柛鏇ㄥ亞閸婄偛顪冮妶鍡楃瑐婵炴潙娲畷鎴炵瑹閳ь剙顫忓ú顏勭閹艰揪绲哄Σ鍫濃攽閻橆偄浜惧┑鐑囩祷閸庣敻寮婚敐鍡樺劅闁靛闄勯柨顓炩攽閳藉棗浜濈紒璇插€块崺銏ゅ籍閸喐娅囬梺绋挎湰閼归箖鎮楅銏＄厽閹兼惌鍨抽崚鏉款熆瑜嶅ù椋庡垝?
    private final ThreadPoolExecutor asyncCollectorPool;

    // 4. 闂傚倸鍊峰ù鍥ь浖閵娾晜鍤勯柤绋跨仛濞呯姵淇婇妶鍌氫壕闂佷紮绲介悘姘跺箯閸涘瓨鍋￠柟娈垮枤閻涱噣姊绘担鍛婂暈婵炶绠撳畷銏＄鐎ｎ亞锛欓梺缁樺姉閸庛倝鎮″▎鎴犵＜婵°倓鑳堕埥澶嬩繆椤愵偄寮€殿喓鍔嶇粋鎺斺偓锝庡亞閸?- 闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶銉ョ仾闁稿孩顨嗘穱濠囧Χ閸涱喖娅ｉ梺鍝勵儎缁舵岸寮婚敐鍛傛棃鍩€椤掑嫭鍋嬮柛鈩冪懅閻牓鏌ㄩ弴妤€浜惧銈庡弨濞夋洟骞戦崟顖氫紶闁告洖鍚€缁辨垿姊绘担绛嬪殐闁哥姵鐗犻弫鍐敂閸繃鐎悗骞垮劚椤︻垶骞戦崼鏇熺厸闁告洦鍋掗弫鎺楁⒒娴ｇ瓔鍤欓梺甯到椤洩顦查摶鐐寸節闂堟稒顥戦柡瀣墵閺屻劑寮撮悙娴嬪亾閹间礁鍨傞柛宀€鍋為崐鐢告煥濠靛棙鍣规い锝呯－缁辨帡鍩€?
    private final ThreadPoolExecutor dataProcessorPool;

    // ================== 闂傚倷娴囧畷鍨叏閹绢噮鏁勯柛娑欐綑閻ゎ喗銇勯幇鈺佲偓鎰板磻閹捐埖鍠嗛柛鏇ㄥ亐閺嬫瑩鎮楀▓鍨珮闁稿锕獮鍐樄鐎规洜鍘ч埞鎴﹀醇濞戞ü鍠婄紓鍌氬€搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏉库攽閻樺磭顣查柛?==================

    // 闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇炲€哥壕鍧楁煙鐠哄搫顥為柛銉墻閺佸棝鏌涚仦鍓р槈妞ゅ繆鏅犲娲川婵犲倸顫囬梺鍛婃煥閻倿銆佸▎鎰瘈闁搞儯鍔庨崢钘夆攽閳藉棗鐏犻柣蹇旂箞閹繝骞囬悧鍫㈠帗閻熸粍绮撳畷妤€鈽夊顓у仺闂侀潧鐗嗗ú锕傦綖閺囥垺鐓熺憸蹇涙倶閸嶇穭ceId -> 闂傚倷娴囧畷鍨叏閹绢噮鏁勯柛娑欐綑閻ゎ喗銇勯幇鈺佲偓鎰板磻閹捐埖鍏滈柛娑卞枤瑜把呯磽娴ｅ摜鐒稿ù婊冪埣瀹曟椽宕熼姘鳖槰閻熸粌绻掔划鍫ュ焵?
    private final Map<String, DeviceScheduleInfo> deviceScheduleInfo = new ConcurrentHashMap<>();

    // Time-slice task buckets
    private final Map<Integer, List<DeviceBatchTask>> timeSliceTasks = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> timeSliceScheduleFutures = new ConcurrentHashMap<>();

    // 闂傚倸鍊搁崐鐑芥倿閿曗偓椤灝螣閼测晝鐓嬮梺鍓插亝濞叉﹢宕戦鍫熺厱闁斥晛鍟伴埥澶愭煕濡や礁鈻曢柟顔筋殔閳藉鈻嶉搹顐㈢伌闁诡噯绻濋崺鈧い鎺戝閳锋帒霉閿濆洦鍤€缂佽尪宕电槐鎺撳緞鐎ｎ偄鍞夊Δ鐘靛仜濡瑩骞嗛弮鍫澪╅柨鏃囶潐鐎垫垿姊绘担鍛婂暈闁荤喆鍎佃棟妞ゆ牜鍎戠紞鏍偓骞垮劚椤︿即鎮￠妷锔剧闁瑰鍋熼幊鍛存煙閻ｅ苯鐨焩iceId -> 闂備浇顕ф鎼佹倶濮橆剦鐔嗘慨妞诲亾妤犵偛锕ㄧ粻娑㈠籍閳ь剙鈻嶉悩缁樼厽闁挎繂鎳忓﹢浼存煕鐎ｎ偒鐒介柍褜鍓欓崢婊堝磻閹剧粯鐓曢柡鍥ュ妼娴滄繃銇勮箛鏇炴灈婵﹥妞藉畷銊︾節閸屾碍鐦撻梻浣稿閻撳牓宕归崼鏇犲祦闁硅揪绠戦～鍛存煏閸繃鍣芥い?
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private static final long CONFIG_RESTART_DEBOUNCE_MS = 1000L;

    // 闂傚倸鍊峰ù鍥敋閺嶎厼纾块柟闂寸楠炪垺淇婇妶鍌氫壕濡ょ姷鍋涢鍥╂閹捐纾兼繛鍡樺笒閸橈繝姊洪崫銉バｆ繛鑼枛閵?
    private final PerformanceMonitor performanceMonitor = new PerformanceMonitor();

    // 闂傚倷娴囧畷鍨叏閹绢噮鏁勯柛娑欐綑閻ゎ喗銇勯幇鈺佲偓鎰板磻閹捐埖鍠嗛柛鏇ㄥ亐閺嬫瑩姊洪崫鍕潶闁告柨绉剁划顓㈡偄閸濄儳鐦堥梺鍛婃处閸欏骸危閻戣姤鈷掗柛灞捐壘閳ь剚鎮傚畷顖烆敃閵忊晛娈ㄩ梺鍓插亝濞诧箓寮崱妯肩闁瑰瓨鐟ラ悘顏堟煟閹惧啿鈧鍩€椤掆偓缁犲秹宕曢柆宥呯閻庯綆鈧叏绲剧换婵嗩潩椤撶偘鍖栭梺璇插嚱缂嶅棙绂嶅鍫濇辈婵炲棙鎸婚崐?
    private final ReentrantLock scheduleLock = new ReentrantLock();
    // 闂傚倸鍊风粈渚€骞栭锕€鐤い鎰堕檮閸嬪鏌ｉ幘鍐差唫婵炴垯鍨归柨銈嗕繆閵堝嫮顦﹀ù婊堜憾濮婃椽骞栭悙鎻掑Ф闂佸憡鎸婚悷褔寮鑲╂殾闁搞儮鏅濋敍?
    private AtomicInteger TIME_SLICE_COUNT = new AtomicInteger(2);          // 闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁硅揪绲剧涵鍫曟煕閺傝法校濞ｅ洤锕幃娆撳箵閹哄棗浜鹃柛顭戝櫘濞兼牠鏌ゆ慨鎰偓鎰板磻閹剧粯鍋ㄦ繛鍫ｆ硶閸旂顪冮妶蹇曞埌妞ゎ厾鍏樺濠氬焺閸愨晛顎撻悗鐟板濠㈡绮婇鈧?
    private AtomicInteger TIME_SLICE_INTERVAL = new AtomicInteger(1000);    // 闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁硅揪绲剧涵鍫曟煕閺傝法校濞ｅ洤锕幃娆撳箵閹哄棗浜鹃柛顭戝櫘濞兼牠鏌ゆ慨鎰偓鎰板磻閹剧粯鍋ㄦ繛鍫ｆ硶閸旂顪冮妶蹇曞埌妞ゎ厼鍢查～蹇旂節濮橆剛锛滃┑鐐村灦閻熴儵宕径濞炬斀闁绘劘灏欓崹鎶芥煕閵夛絽濡芥繛鍛墵濮婅櫣绮旈崱妤佹拱婵炲牆鐭傚?
    private TimeSliceTuner timeSliceTuner;

    @Autowired
    public CollectionScheduler(
            @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler,
            @Qualifier("batchDispatcherExecutor") ExecutorService batchDispatcher,
            @Qualifier("asyncCollectorExecutor") ThreadPoolExecutor asyncCollectorPool,
            @Qualifier("dataProcessorExecutor") ThreadPoolExecutor dataProcessorPool) {
        this.timeSliceScheduler = timeSliceScheduler;
        this.batchDispatcher = batchDispatcher;
        this.asyncCollectorPool = asyncCollectorPool;
        this.dataProcessorPool = dataProcessorPool;
    }

    public PerformanceStatsSnapshot getPerformanceSnapshot() {
        return PerformanceStatsSnapshot.builder()
                .timeSliceCount(TIME_SLICE_COUNT.get())
                .timeSliceIntervalMs(TIME_SLICE_INTERVAL.get())
                .timeSliceExecutionTimes(performanceMonitor.getTimeSliceExecutionTimesSnapshot())
                .overloadedSlices(performanceMonitor.getOverloadedSlicesSnapshot())
                .slowestDevices(performanceMonitor.getSlowestDevicesSnapshot())
                .deviceStats(performanceMonitor.getAllDevicePerformance())
                .build();
    }

    @PostConstruct
    public void init() {
        // 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旂厧顫╅柣搴㈣壘椤﹂亶鍩€椤掆偓缁犲秹宕曢柆宥呯疇閹兼惌鐓夌紞鏍煏閸繍妲归柣鎾存礋閺屻劌鈹戦崱妞诲亾閻㈢纾婚柟鎹愵嚙缁犲灚鎱ㄥ┑鍡涘弰婵﹥妞藉畷顐﹀礋椤撳鍊栭妵鍕敇閻愰潧鈪辩紓鍌氬€归幐濠氬Χ閿濆绀冮柕濠忓閳?
        int cpuCores = Runtime.getRuntime().availableProcessors();
        // 1. 闂傚倷娴囧畷鍨叏瀹曞洦顐介柕鍫濇处椤洟鏌￠崶銉ョ仾闁稿鏅涢埞鎴︽偐閾忣偆娈ら悗瑙勬尫缁舵岸寮婚悢纰辨晬闁绘劘寮撻崰濠囨⒑閸濄儱鏋庢繛鎾棑濡叉劙骞樼拠鑼槰闂佽鍨庨崟顓濇喚闂傚倷鑳剁划顖炴偋椤撶姷绀婂ù锝呮憸閺嗭附绻涢崱妯诲碍妤犵偑鍨烘穱濠囧Χ閸屾矮澹曟俊鐐€栧▔锕傚炊閵娧冨箺闂備線娼ч…鍫ュ磿闁稁鏁傛い蹇撴噸缁诲棝鏌ｉ幇顓炵祷闁逞屽墮閻忔繈锝炶箛娑欏殥闁靛牆鍊告禍楣冩煟閵忊槅鍟忛柣鎺斿亾閵囧嫰鏁愰崪浣哄悑闂佸搫澶囬崜婵嬪箯閸涱厾鏆嬮柡澶庢硶閵堫噣姊?
        // 闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煛閸ャ儱鐏╅柛銊ュ€块弻娑⑩€﹂幋婵呯凹闂佸憡鐟ョ换姗€骞冨Δ鍛櫜閹肩补鈧尙鍑规俊鐐紖娴ｅ憡澶勯柍閿嬪浮閺屾稓浠﹂幆褍姣堥梺鎼炲€楅崰搴ㄢ€﹂懗顖ｆЪ闂佺粯顨堟慨鎾敋閿濆鏁冮柨婵嗘媼閸ゃ倝姊洪崫鍕垫Ч闁搞劌鐖奸、鏃堟晸閻樻枼鎷洪梺鍛婄☉閿曪箓鎯屾繝鍥ㄧ厸闁割偒鍋勬晶顕€鏌曢崱妯烘诞闁硅櫕绮撳Λ鍐ㄢ槈濮樿京宓侀梺鑽ゅ枑缁本顨ラ幖浣测偓鏃堝礃椤旇偐锛滈梺缁樺姉鐞涖儵骞忔繝姘厽閹艰揪绲鹃弳鈺傘亜椤撶偟澧﹂柕鍡楀€圭换婵嗩潩椤撶姴骞愬┑鐐舵彧缁蹭粙宕查幓鎺旀殼闁糕剝绋掗悡鏇㈡煥濠靛棙顥為柕鍥ㄧ箞閺岀喖顢涘顒佹閻庤娲滈崰鏍€佸▎鎾崇鐟滃繐鐣峰ú顏呪拺閻犲洩灏欑粻鎵磼缂佹ê濮夐柟骞垮灲楠炲洭寮剁捄銊ュЕ婵＄偑鍊栫敮鎺楀磹閸洖鐒垫い鎺嗗亾妞ゆ垵娲ゅ嵄闁归偊鍏橀弨浠嬫倵閿濆簼绨芥い锔哄姂閹鐛崹顔煎闁诲孩鍑归崳锝夊箖?闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煟閹邦剛浠涙い鎰Г缁绘繈妫冨☉娆愭倷缂備礁澧庨崑鐔煎箟缁嬫鍚嬪璺猴功閸旓箑顪冮妶鍡楃瑨闁稿﹤鐖奸崺鈧い鎺嗗亾妞ゆ垵娲ゅ嵄闁归偊鍏橀弸搴ㄦ煙鐎电浠滈柛妯兼嚀椤啴濡堕崱姗嗘⒖閻庤娲滈弫鍝ュ垝缂佹顩烽悗锝庡亞閸橀箖鎮楅悷鏉款仾婵犮垺锕㈠鎶芥焼瀹ュ棛鍘?濠电姷鏁搁崑鐐哄垂閸洖绠归柍鍝勫€婚々鍙夌節闂堟稒锛嶆繛鎾愁煼閺屾稑鐣濋埀顒勫磻閻愬搫鐓曢柟鐑橆殕閸婄敻鏌ｉ姀鈽嗗晱闁绘帞鍋撻妵鍕晲閸滀胶鍚嬮梺?
        int normalizedSliceCount = Math.max(1, Math.min(
                collectorProperties.getScheduler().getInitialTimeSliceCount(), // 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼棯閹勮础闁汇儺浜、姗€濮€閳哄偆妫栫紓?
                collectorProperties.getScheduler().getMaxTimeSliceCount()      // 闂傚倸鍊风粈渚€骞栭锔藉亱闁告劦鍠栫壕濠氭煙閻愵剙澧柣鏂挎閺屾盯顢曢姀鈽嗘闂佸摜鍠撴繛鈧€规洘鍨块獮姗€鎳滈棃娑樺箲濠碉紕鍋涢鍛归悜鑺ュ仒?2
        ));
        TIME_SLICE_COUNT.set(normalizedSliceCount); // 缂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏉库攽閻樺磭顣查柛濠呮硾椤潡鎳滈棃娑橆潔闂?闂?闂?2闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煛閸ャ儱鐏柛搴＄У閵囧嫰骞掗崱妞惧婵?闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸ゆ劖銇勯弽顐粶闁汇値鍣ｉ弻宥堫檨闁告挾鍠栧?闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煛閸ャ儱鐏╁鍛存⒑閹肩偛鍔€閻忕偠濮ら?闂?

        // 2. 闂傚倷娴囧畷鍨叏瀹曞洦顐介柕鍫濇处椤洟鏌￠崶銉ョ仾闁稿鏅涢埞鎴︽偐閾忣偆娈ら悗瑙勬尫缁舵岸寮婚悢纰辨晬闁绘劘寮撻崰濠囨⒑閸濄儱鏋庢繛鎾棑濡叉劙骞樼拠鑼槰闂佽鍨庨崟顓濇喚闂傚倷鑳剁划顖炴偋椤撶姷绀婂ù锝呮憸閺嗭附绻涢崱妯诲碍妤犵偑鍨烘穱濠囧Χ閸屾矮澹曟俊鐐€栧▔锕傚炊閵娧冨箺闂備線娼ч…鍫ュ磿闁稁鏁傛い蹇撴噸缁诲棝鏌ｉ幇顓炵祷闁逞屽墮閻忔繈锝炶箛娑欏殥闁靛牆鍊告禍楣冩煟閵忊槅鍟忛柣鎺斿亾閵囧嫰鏁愰崪浣哄悑濠殿喖锕ら…宄扮暦閹烘垟妲堟繛鍡楃箰閺€顓熺節?
        // 闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣繆閵堝倸浜鹃梺闈涙鐢€崇暦濠婂嫭濯村瀣婢ч箖姊绘繝搴′簻婵炶绠戦～蹇氥亹閹广倕娲╅ˇ瑙勬叏婵犲偆鐓肩€规洘甯掗～婵嬫偂鎼淬埄鍟€婵犵數濮幏鍐礋椤撶偐鎷版繝娈垮枛閿曘倝鈥﹂悜鐣屽祦婵せ鍋撴い銏＄懅缁數鈧綆浜濋崑鎰版⒒閸屾瑧顦﹂柟璇х磿閸掓帒鐣濋崟顒€娈ｅ銈嗙墱閸嬫盯宕归崒鐐寸厱妞ゎ厽鍨垫禍婵嬫煕濞嗗繒绠婚柟顔筋殜閺佹劖鎯旈垾鑼嚬婵＄偑浼呮担鍛婂闁抽攱甯￠弻娑氫沪閹冩瘓闂佹悶鍊楅崰搴ㄢ€﹂懗顖ｆЪ闂佺粯顨堟慨鎾綖韫囨梻绡€婵﹩鍓涢鎺戭渻閵堝棙鈷掗柣鈩冩瀹曘儱顫滈埀顒€顫忛崫鍕懷囧炊瑜嶉‖鍫ユ煟鎼淬垹鍤柛銊ュ暱閳诲酣濮€閻橆偅鏂€闂佹悶鍎滈崨顖滃祦闂佽崵鍠愮划宀€绮旇ぐ鎺戞槬闁?
        int normalizedInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),    // 闂傚倸鍊风粈渚€骞栭锔藉亱闁告劦鍠栫壕濠氭煙閸撗呭笡闁稿﹤鐖奸悡顐﹀炊閵婏箑鏆楃紓浣哄С閸楁娊寮诲鍫闂佸憡鎸婚惄顖涗繆鐎电硶鍋撻敐搴″妞?00ms
                collectorProperties.getScheduler().getInitialTimeSliceIntervalMs() // 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼棯閹勮础闁汇儺浜、姗€濮€閳哄偆妫栫紓?500ms
        );
        TIME_SLICE_INTERVAL.set(normalizedInterval); // 缂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏉库攽閻樺磭顣查柛濠呮硾椤潡鎳滈棃娑橆潔闂?500ms闂?00闂?500闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煛閸ャ儱鐏╁鍛存⒑閹肩偛鍔€閻忕偠濮ら?500闂?

        // 3. 闂傚倷娴囧畷鍨叏瀹曞洦顐介柕鍫濇处椤洟鏌￠崶銉ョ仾闁稿鏅涢埞鎴︽偐鐎圭姴顥濋柣搴㈣壘椤︾敻寮诲鍫闂佸憡鎸婚懝楣冾敋閵夆晛绀嬫い鏇炴噺閳诲本绻濈喊妯活潑闁稿瀚…鍥灳閹颁焦缍庢俊銈忕到閸燁偄螞濮椻偓閺屻倝骞囨担鍝ヤ哗闂佺粯绻嶉崹璺侯潖濞差亜宸濆┑鐘插€婚悷鑼磽娴ｅ壊鍎愰柛銊ユ贡濡叉劙骞樼拠鑼啋闁荤姴娲╃亸娆撴儓閸曨垱鈷戦柛鎾村絻娴滄繃绻涢崣澶涜€块柡浣哥Ч閹垽宕楅懖鈺佸箺婵犲痉鏉库偓鎰板磻閹惧绠炬繛鏉戭儐濞呭﹪鏌熼姘殻闁诡喓鍨藉畷妤呮偂鎼淬垻鏋€闂傚倷鑳堕…鍫ュ嫉椤掑倸鏋堢€广儱顦伴崑?
        // 闂傚倸鍊风粈渚€骞栭锔藉亱闁告劦鍠栫壕濠氭煙閻愵剙澧柣鏂挎閺屾盯顢曢姀鈽嗘濠电偠顕滅粻鎾诲极瀹ュ鍋勯柛蹇撶毞閹?= 濠电姵顔栭崰妤冩暜濡ゅ啰鐭欓柟鐑樸仜閳ь剨绠撳畷濂稿Ψ椤旇姤娅嶅┑鐘垫暩婵敻鎳濋崜褏灏电€广儱顦伴悡鏇熴亜閹板墎绋荤紒鈧崘顔界叆婵炴垶鑹鹃弸娑㈡煛瀹€瀣埌閾绘牠鏌嶈閸撴瑧鍙呭銈嗘尵閸婏絽鈽夐姀鐘绘暅濠德板€撶拋鏌ュ箰閸曨垱鈷? 濠?闂傚倸鍊风粈渚€骞栭銈囩煋闁哄鍤氬ú顏勎у璺猴躬濡嘲顪冮妶鍡欏⒈闁稿绋撶划鍫濈暆閸曨剛鍘搁悗骞垮劚妤犳悂鐛Δ鍛叆婵炴垶鑹鹃弸娑欐叏?闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煟閹邦剛浠涙い鎰Г缁绘繈妫冨☉娆愭倷缂備礁澧庨崑鐔煎箟缁嬫鍚嬪璺猴功閸?
        int maxInterval = Math.max(
                collectorProperties.getScheduler().getDefaultTimeSliceIntervalMs() * 2, // 濠电姵顔栭崰妤冩暜濡ゅ啰鐭欓柟鐑樸仜閳ь剨绠撳畷濂稿Ψ椤旇姤娅嶅┑鐘垫暩婵敻鎳濋崜褍顥?500闂?=3000ms
                normalizedInterval                                                      // 闂傚倸鍊风粈渚€骞栭銈囩煋闁哄鍤氬ú顏勎у璺猴躬濡嘲顪冮妶鍡欏⒈闁稿绋撶划鍫濈暆閸曨剛鍘搁悗骞垮劚妤犳悂鐛Δ鍛叆婵炴垶鑹鹃弸娑欐叏婵犲偆鐓肩€规洏鍔戦、娆戠驳鐎ｎ偒妫濈紓?500ms
        ); // 缂傚倸鍊搁崐鎼佸磹閻戣姤鍊块柨鏇炲€哥粻鏉库攽閻樺磭顣查柛濠呮硾椤潡鎳滈棃娑橆潔闂?000ms闂?000闂?500闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洖鍊归崑瀣煛閸ャ儱鐏╁鍛存⒑閹肩偛鍔€閻忕偠濮ら?000闂?

        // 4. 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟伴惌娆撴煙鐎电啸缁惧彞绮欓弻鐔煎箲閹伴潧娈紓渚囧亜缁夊綊寮诲☉銏╂晝闁挎繂妫涢ˇ銊╂⒑閹稿海銆掗柛鐘崇墵瀵鏁愰崨鍌涙閸┾偓妞ゆ巻鍋撻悡銈夋煃閸濆嫭鍣洪柛搴＄Ч閺岋綁寮崹顔藉€梺鎼炲€曠粔褰掑蓟濞戞矮娌柛鎾楀本娈归梻浣虹帛缁哄潡宕愬┑瀣畺婵°倕鎳庨幑鑸点亜閹捐泛浠掔紒顔ㄥ懐纾?
        // 闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁靛鏅涚壕鍦喐閻楀牆绗掓慨瑙勭叀閺岋綁寮崹顔藉€梺鍝勬媼閸撴岸骞堥妸銉建闁糕剝顨呯粻鐑樼箾鐎电校闁挎洏鍨介獮鍐ㄎ熺捄銊ф澑闂佽鍎虫晶搴敊閹烘垟鏀介柍钘夋娴滄粍绻涚亸鏍ゅ亾閹颁焦缍?300ms)闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈剧畱鐎氬銇勯幒鍡椾壕闂侀潧妫欑敮锟犲箠濠婂牊顥堟繛鎴炴皑閻涱噣鏌ｉ悢鍝ョ煁闁哄被鍔忛悘瀣⒑缁夊棗瀚峰▓鏇㈡煃?3000ms)闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸ゆ劖銇勯弽顐粶闁搞劌鍊块弻娑⑩€﹂幋婵呯凹闂佸憡鐟ョ换姗€骞冨Δ鍛櫜閹肩补鍓濋悘宥夋⒑閹稿海銆掗柛鐘崇墪椤?1500ms)
        this.timeSliceTuner = new TimeSliceTuner(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(), // 300ms
                maxInterval,                                                    // 3000ms
                normalizedInterval                                              // 1500ms
        );

        // 缂傚倸鍊搁崐鐑芥倿閿曗偓閻ｅ嘲螣鐞涒剝鐏冮梺鍝勬储閸ㄥ綊鎮″┑瀣厽闁规儳鍟块悵鎰喐閺冨牆绠栭柍鍝勬噹閸ㄥ倹銇勯幇鍓佺ɑ妞?ThreadPoolConfig 缂傚倸鍊搁崐鎼佸磹閻戣姤鍤勯柛顐ｆ礀缁愭鈧箍鍎卞ú銊╁础濮樿埖鐓涘璺侯儏閻忓秹鏌＄€ｎ偆澧甸柡灞炬礃缁绘盯宕归鐓庮潛闁诲孩顔栭崰妤佺箾婵犲洤钃熼柕濞垮劗濡插牊淇婇婵嗕汗濞寸厧娲娲倷閽樺濮曢柦鍐含缁辨帞浠﹂挊澶庡煘闂佸疇顫夐崹鍨暦閵娾晩鏁嶆繝濠傚閺嬶箓姊绘担钘夊惞濠殿喗鎸抽、鏍川閺夋垹鏌堝銈嗗姂閸婃劙宕戦幘鑸靛枂闁告洦鍋€閺嬫瑩鎮楃憴鍕濠电偛锕ユ穱濠囧箹娴ｅ摜鍘告繛杈剧稻閻℃洜绮婄€靛摜纾介柛灞捐壘閳ь剚鎮傚畷鏉款潩鐠鸿櫣鐛ラ梺鍝勬川婵兘宕″鑸电厵缂備降鍨归弸鐔兼煕?
        // 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼磼閳ь剙鐣濋崟顒傚幐閻庡箍鍎遍崯顐ｄ繆閼恒儯浜滈敎濠氬炊閵娿垺瀚介梻浣侯焾閺堫剟宕欒ぐ鎺戝惞闁绘柨顨庨悢鍡欐喐鎼淬劍鍋嬫俊銈呭暟閻鏌熼悜妯烩拹閻庢碍宀搁弻鐔虹磼濡桨鍒婂┑鐐插悑婵炲﹪寮?
        resetTimeSliceTaskBuckets(TIME_SLICE_COUNT.get());

        // 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼椤愩垻效闁哄本鐩、鏇㈡晲閸℃瑯妲伴梻浣瑰缁嬫捇宕伴弽顓炶摕闁挎繂鎲橀弮鍫濈劦妞ゆ巻鍋撻悡銈夋煃閸濆嫭鍣洪柛搴＄Ч閺岋綁寮幐搴℃殘闂?
        startTimeSliceScheduling();

        // 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼閳ь剚寰勯幇顓犲帾婵犵數濮寸换妤呭触閸岀偞鐓曢悗锝庡亝鐏忎即鏌熷畡鐗堝櫧闁瑰弶鎸冲畷鐔煎垂椤愬秵绻堝濠氬磼濮橆兘鍋撻幖浣哥９闁归棿绶￠弫瀣亜閹捐泛鏋戞繛鍛█閺屸€愁吋鎼粹€茬凹闁诲孩鍑归崜鐔煎蓟濞戙垹绠涢柕濠忛檮閻濇洖鈹?
        startDynamicTimeSliceAdjustment();

        // 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼閻樺啿鐏╅柟鍙夋倐楠炲鏁冮埀顒傜矆閸屾稒鍙忔俊鐐额嚙娴滈箖姊虹拠鈥崇仩閻庢矮鍗抽悰顔锯偓锝庡枟閸婄兘妫呴顐㈠箻閻?
        startPerformanceMonitoring();

        log.info("濠电姴鐥夐弶搴撳亾閺囥垹纾圭痪顓炴噺閹冲矂姊绘笟鈧埀顒傚仜閼活垶宕濋鐐寸厱闁斥晛鍠氬▓鏃€顨ラ悙宸剶婵﹦绮幏鍛存偡閹殿喚銈锋繝鐢靛仜濡﹪宕㈡總绋跨厺闁哄啫鐗婇弲婊堟煟閹伴潧澧い蹇婃櫊濮婃椽宕ㄦ繝鍌氼潎闂佸憡鏌ㄩ惌鍌炪€佸▎鎰瘈闁搞儯鍔庨崢閬嶆⒑閹稿海绠撻柛鐕佸亝娣囧﹥绺介崨濠勫幈闂侀潧顭堥崕铏濞戙垺鐓欏〒姘仢婵″潡鏌嶈閸撱劎绱為崱妯碱洸婵犲﹤鐗滈弫瀣喐閺冨牆绠栨俊銈呮噺閸嬶繝鏌ｅΔ鈧悧濠囧箯闁秵鈷戦柟鑲╁仜婵＄晫鈧厜鍋撻柟闂寸閺嬩胶鈧箍鍎卞ú鐘诲磻閹炬枼妲堟俊顖炴敱閺呮矾闂傚倸鍊风粈渚€骞栭銈囩煋闁割偅娲嶉埀顒婄畵瀹曞ジ濮€閵忋垹顦╁┑掳鍊х徊浠嬪疮椤愶箑鍑? {}, 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼磼椤愩垻效闁哄本鐩、鏇㈡晲閸℃瑯妲伴梻浣瑰缁嬫捇宕伴弽顓炶摕闁挎繂鎲橀弮鍫濈劦妞ゆ巻鍋撻悡銈夋煥閺囩偛鈧效? {}", cpuCores, TIME_SLICE_COUNT.get());

        // 闂備浇顕уù鐑藉磻閿濆纾归柡宥庡幖绾惧潡鏌ゅù瀣珕鐎规洖寮剁换婵囩節閸屾粌顣洪柣鐔哥懕闂勫嫰濡甸崟顖氬唨闁靛鍔岄ˉ婵嬫偠濮樺崬鏋涙慨濠冩そ瀹曠兘顢樿濮ｅ矂姊虹粙娆惧剱闁圭懓娲濠氭晸閻樻煡鍞跺┑鐘茬仛閸旀牗鐡忓┑锛勫亼閸婃牕顫忛悷鎳婂搫螣娓氼垰娈?
        timeSliceScheduler.schedule(this::autoStartAllDevices, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        log.info("闂備浇顕х€涒晠顢欓弽顓炵獥闁圭儤顨呯壕濠氭煙閻愵剚鐏遍柡鈧懞銉ｄ簻闁哄啫鍊甸幏锟犳煕鎼淬垺顥堥柡宀嬬節閸┾偓妞ゆ帒瀚～鍛存煟濡哀鎴犳暜濡ゅ啰绠旈柣鏃傚帶閻掑灚銇勯幒鎴濐仾闁稿鍠愰妵鍕即濡も偓娴滄儳螖閻橀潧浜归柛瀣崌閹嘲顭ㄩ崟顐や紝閻庤娲橀〃鍛达綖濠婂牆鐒垫い鎺戝閽冪喓鈧厜鍋撻柛鏇ㄥ亞閸婄偛顪冮妶鍡楃瑐婵炴潙娲畷鎴炵瑹閳ь剟寮婚敐澶嬪亹鐎瑰壊鍠栭崜浼存⒑閸濄儱娅忛柛瀣閸┾偓妞ゆ帒鍠氬鎰攽閻愯宸ラ柣?..");

        // 闂傚倸鍊烽懗鍫曗€﹂崼銉晞闁糕剝鐟ラ崹婵堚偓骞垮劚椤︿粙寮崱妯肩闁瑰瓨鐟ラ悘鈺冪磼閻橆喖鍔﹂柡灞界Х椤т線鏌涢幘璺烘瀻妞ゆ洩缍佸畷褰掝敃閵忋垻鐛梺鐟板悑閹矂宕伴弽顓炵柧妞ゆ挾鍠撶弧鈧?
        stopAllDevices();

        // 闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁瑰鍋熺粻鎯р攽閻樿弓杩规繛鎴炲焹閸嬫捇妫冨☉娆愬枑濠碘槅鍋掗崹鍫曞箖瑜版帒绠掗柟鐑樺灥鍞梻浣告惈鐞氼偊宕愰弽顓炵疄?
        shutdownExecutor("timeSliceScheduler", timeSliceScheduler);
        shutdownExecutor("batchDispatcher", batchDispatcher);
        shutdownExecutor("asyncCollectorPool", asyncCollectorPool);
        shutdownExecutor("dataProcessorPool", dataProcessorPool);

        // 婵犵數濮烽弫鎼佸磻閻愬搫绠伴柟闂寸缁犵娀鏌熼悧鍫熺凡闁绘挻锕㈤弻鈥愁吋鎼粹€崇闂佸搫顑勭欢姘跺蓟閿濆憘鏃堝焵椤掑嫭鍋嬮柛鈩冪懅閻牓鏌ㄩ弬鍨挃缁炬崘妫勯湁闁挎繂鐗滃鎰版倶韫囧骸宓嗛柡?
        deviceScheduleInfo.clear();
        cancelTimeSliceScheduling();
        timeSliceTasks.clear();
        timeSliceScheduleFutures.clear();
        pendingConfigRestartTasks.values().forEach(future -> future.cancel(false));
        pendingConfigRestartTasks.clear();

        log.info("CollectionScheduler destroyed");
    }

    /**
     * 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼椤愩垻效闁哄本鐩、鏇㈡晲閸℃瑯妲伴梻浣瑰缁嬫捇宕伴弽顓炶摕闁挎繂鎲橀弮鍫濈劦妞ゆ巻鍋撻悡銈夋煃閸濆嫭鍣洪柛搴＄Ч閺岋綁寮幐搴℃殘闂?
     */
    private void startTimeSliceScheduling() {
        cancelTimeSliceScheduling();
        int sliceCount = Math.max(1, TIME_SLICE_COUNT.get());
        int sliceInterval = Math.max(1, TIME_SLICE_INTERVAL.get());
        
        for (int sliceIndex = 0; sliceIndex < sliceCount; sliceIndex++) {
            final int currentSlice = sliceIndex;

            // 婵犵數濮甸鏍闯椤栨粌绶ら柣锝呮湰瀹曟煡鎮楅敐搴℃灍闁绘挸鍊圭换婵囩節閸屾粌顣虹紓渚囧亜缁夊綊寮诲☉銏╂晝闁挎繂妫涢ˇ銊╂⒑閹稿海銆掗柛鐘崇墵瀵鏁愰崨鍌涙閸┾偓妞ゆ巻鍋撻悡銈夋煏閸繍妲哥紒鐘冲哺楠炴牕菐椤掆偓婵¤偐绱掗埀顒佸緞閹邦厾鍘繝鐢靛仜閻忔繈鍩€椤掍緡娈滄い銏℃瀹曪繝鎮欓埡鍌ゆ綌闂備胶鎳撻悘婵嬪疮閳轰急褰掓倻閼恒儳鍘介梺闈浤涢崘鈺冣偓楣冩⒑鐠団€崇仭婵☆偄鍟村顐﹀磼閻愯尙顔囬柟鑲╄ˉ閳ь剙纾崫搴ㄦ⒒?
            ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(() -> {
                try {
                    executeTimeSlice(currentSlice);
                } catch (Exception e) {
                    log.error("Time slice {} execution failed", currentSlice, e);
                }
            }, (long) sliceIndex * sliceInterval, (long) sliceInterval * sliceCount, TimeUnit.MILLISECONDS);
            timeSliceScheduleFutures.put(currentSlice, future);
        }

        log.info("闂傚倸鍊风粈渚€骞栭锕€鐤い鎰堕檮閸嬪鏌ｉ幘鍐差唫婵炴垯鍨归柨銈嗕繆閵堝嫮顦﹀ù婊堜憾濮婃椽骞栭悙鎻掑Ф婵炲瓨绮庨崑娑㈠煝閹捐閱囬柡鍥╁仧椤旀劙鏌℃径濠勫濠⒀傜矙閹箖鏌嗗鍡欏幈闂佽鎯岄崹宕囧姬閳ь剟鎮楃憴鍕８闁稿海鏁婚妴浣糕槈濮楀棙鍍靛銈嗗焾閸嬪懘銆冩繝鍥ц摕闁靛鍎Σ鍫熶繆椤栨氨浠㈡い蹇ｅ弮濮?{} 濠电姷鏁搁崑鐐哄垂閸洖绠归柍鍝勫€婚々鍙夌節闂堟稒锛嶆繛鎾愁煼閺屾稑鐣濋埀顒勫磻閻愬搫鐓曢柟鐑橆殕閸婄敻鏌ｉ姀鈽嗗晱闁绘帞鍋撻妵鍕晲閸滀胶鍚嬮梺鍝勮閸斿矂鍩ユ径濞㈢喓绱掑Ο璇差伆闂傚倷妞掔槐顔炬媼閿濆洦宕叉繝闈涱儏缁?{}ms", sliceCount, sliceInterval);
    }

    /**
     * 闂傚倸鍊风粈浣革耿闁秵鍋￠柟鎯版楠炪垽鏌嶉崫鍕偓褰掑级閹间焦鈷掑ù锝呮啞鐠愨剝銇勯鐐靛閻撱倝鏌曢崼婵愭Ч闁稿鏅涜灃闁挎繂鎳庨弳娆戠磼椤愩垻效闁哄本鐩、鏇㈡晲閸℃瑯妲伴梻浣瑰缁嬫捇宕伴弽顓炶摕闁挎繂鎲橀弮鍫濈劦妞ゆ巻鍋撻悡銈夋煏婵炲灝鍔楅柡瀣閺岀喓鈧數顭堟禒褎銇勯埡鍐ㄥ幋闁诡喖缍婂畷鍫曞Ω閵壯呫偡婵?
     */
    private void cancelTimeSliceScheduling() {
        timeSliceScheduleFutures.values().forEach(future -> future.cancel(false));
        timeSliceScheduleFutures.clear();
    }

    private void resetTimeSliceTaskBuckets(int sliceCount) {
        timeSliceTasks.clear();
        for (int i = 0; i < Math.max(1, sliceCount); i++) {
            timeSliceTasks.put(i, new CopyOnWriteArrayList<>());
        }
    }

    private void executeTimeSlice(int sliceIndex) {
        long startTime = System.currentTimeMillis();
        int currentSliceInterval = TIME_SLICE_INTERVAL.get();

        try {
            List<DeviceBatchTask> tasks = timeSliceTasks.get(sliceIndex);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }

            // 婵犲痉鏉库偓妤佹叏閻戣棄纾婚柣妯荤ゴ閺嬫牗绻涢幋鐐叉疇濞存粌缍婇弻娑樼暆閳ь剟宕戝☉姘变笉闁靛／鍕瀾闂佺鎻拹鐔煎焵椤戣法绐旀鐐差儔閺佸啴鍩€椤掑倵鍋撳顒夋Ч闁靛洤瀚伴獮鎺戭吋閸パ冾瀴婵＄偑浼呮担鍛婂闁抽攱甯￠弻娑氫沪閹冩瘓闂佹悶鍊楅崰搴ㄢ€﹂懗顖ｆЪ闂佺粯顨堟繛鈧鐐叉瀹曟﹢顢欓懖鈺婃Ч婵＄偑鍊栧濠氬磻閹炬番浜滈柨婵嗘閸欌偓闂佸搫鐭夌换婵嗙暦閻撳簶鏀介柟閭﹀帨閵夆晜鈷戦柣鐔稿閻ｎ參鏌涢妸銊︻棄闁?
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (DeviceBatchTask task : tasks) {
                if (task.shouldSkip()) {
                    continue;
                }

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        processDeviceBatch(task);
                    } catch (Exception e) {
                        log.error("濠电姷鏁告慨浼村垂閻撳簶鏋栨繛鎴炲焹閸嬫挸顫濋悡搴㈢彎濡ょ姷鍋涢崯顖滄崲濠靛绀嬫い鎺嗗亾妞ゅ孩鐩铏规嫚閳ュ磭鈧鎮橀悙鏉戠彑闁挎繂顦伴埛鎴︽⒑椤愶絿銆掔紒渚€鏀遍妵鍕敃閵忊晜鈻堝銈冨灪濞茬喎鐣峰Δ鍛亗閹艰揪绲块悰顕€姊洪崫鍕垫Ц闁绘绮撳畷鎴炲緞鎼搭喗婢? {}", task.deviceId, e);
                    }
                }, batchDispatcher);

                futures.add(future);
            }

            // 缂傚倸鍊搁崐鐑芥倿閿斿墽鐭欓柟娆¤娲、娑橆煥閸曢潧浠洪梻浣虹帛濮婂宕㈣閳ь剚鑹鹃ˇ浼村Φ閸曨垰绠崇€广儱娲ゆ俊钘夘渻閵囶垯绀佸ú锕傚煕閹烘鐓曢悘鐐村劤閸ゎ剟鏌涢妶鍥ф瀻妞ゎ叀鍎婚ˇ鎶芥煟濡ゅ啫鈻堥柣娑卞枛椤撳吋寰勬繝鍕Е婵＄偑鍊栫敮鎺斺偓姘煎墰閳ь剚鑹鹃ˇ顖炩€︾捄銊﹀磯濞撴凹鍨辨晥闁诲氦顫夊ú姗€鎮￠敓鐘茶摕婵炴垯鍨瑰Λ姗€鎮跺☉鎺嗗亾閸忓懎顥氶柣搴ゎ潐濞叉牕煤閿曗偓閳绘捇顢氶埀顒€顫忓ú顏呭仭闁规鍠楅幉濂告⒑閸涘﹥鐏濋柛娑卞櫘濞肩喎鈹戦悙鍙夘棡闁圭鎽滈埀顒佽壘椤︻垶鈥︾捄銊﹀磯闁绘垶蓱瀹曟娊鎮楀☉娆戠疄婵﹥妞藉畷顐﹀礋椤愶絾顔勯梻浣虹帛閻楁粓宕㈣閳ユ牗绻濋崑顖涙そ椤㈡棃宕熼褎孝闂備浇宕垫慨宕囩矆娴ｉ潻鑰块弶鍫氭櫇閻?
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(currentSliceInterval - 10, TimeUnit.MILLISECONDS); // 闂?0ms濠电姷鏁搁崑鐘诲箵椤忓棗绶ゅΔ锝呭暞閸嬶繝鏌嶉崫鍕櫣闂?
            } catch (TimeoutException e) {
                log.warn("Time slice {} execution timed out", sliceIndex);
            } catch (Exception e) {
                log.error("Time slice {} execution failed", sliceIndex, e);
            }

        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordTimeSliceExecution(sliceIndex, executionTime,TIME_SLICE_INTERVAL);

            if (executionTime > currentSliceInterval) {
                log.warn("闂傚倸鍊风粈渚€骞栭锕€鐤い鎰堕檮閸嬪鏌ｉ幘鍐差唫婵炴垯鍨归柨銈嗕繆閵堝嫮顦﹀ù?{} 闂傚倸鍊风粈浣革耿闁秵鍋￠柟鎯版楠炪垽鏌嶉崫鍕偓褰掑级閹间焦鈷掑ù锝呮啞閹牓鏌涢悤浣镐喊闁诡喓鍎甸幃锟犵嵁?{}ms 闂傚倷鑳堕崕鐢稿礈濠靛牊鏆滈柟鐑橆殔缁犵娀鏌熼幑鎰厫鐎规洘鐓￠弻娑㈠箛闂堟稒鐏嶉梺鎶芥敱濡啴寮婚悢鍏肩劷闁挎洍鍋撳褜鍣ｉ弻?{}ms",
                        sliceIndex, executionTime, currentSliceInterval);
            }
        }
    }

    /**
     * 濠电姷鏁告慨浼村垂閻撳簶鏋栨繛鎴炲焹閸嬫挸顫濋悡搴㈢彎濡ょ姷鍋涢崯顖滄崲濠靛绀嬫い鎺嗗亾妞ゅ孩鐩铏规嫚閳ュ磭鈧鎮橀悙鏉戠彑闁挎繂顦伴埛鎴︽⒑椤愶絿銆掔紒渚€鏀遍妵鍕敃閵忊晜鈻堝?
     */
    private void processDeviceBatch(DeviceBatchTask batchTask) {
        String deviceId = batchTask.deviceId;
        List<DataPoint> points = batchTask.points;

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎾翠繆閻愵亜鈧牕顫忛悷鎳婂搫螣娓氼垰娈梺鍛婃处閸ㄤ即鎮欐繝鍐︿簻闊洦鎸婚ˉ鐘炽亜閺冣偓濞茬喎顫忛搹瑙勫枂闁告洦浜ｉ崺鍛存⒑缁嬫寧鍞夋繛鍛礋楠炲牓濡搁埡浣侯啇婵炶揪绲介幗婊堟偩?
            if (!collectionManager.isDeviceConnected(deviceId)) {
                if (!reconnectDevice(deviceId)) {
                    log.warn("Device {} was offline and reconnect failed, skipping batch", deviceId);
                    return;
                }
            }

            // 闂備浇顕х€涒晠顢欓弽顓炵獥闁哄稁鍘肩壕鍦喐閻楀牆淇柡浣告閺岋綁骞囬鐓庡闂佺粯鎸搁崐鍧楀蓟濞戙垹唯妞ゆ梻鍘ч～顏堟⒑閼规澘鐨哄┑鐐╁亾闂佸搫鐬奸崰鎾跺垝椤撶偐妲堟俊顖欑串缁辩偤姊虹悰鈥充壕?
            Future<Map<String, Object>> collectFuture =
                    asyncCollectorPool.submit(() -> {
                        try {
                            return collectionManager.readPoints(deviceId, points);
                        } catch (Exception e) {
                            throw e;
                        }
                    });

            // 闂傚倷鑳堕崕鐢稿礈濠靛牊鏆滈柟鐑橆殔缁犵娀鐓崶銊︽儎婵炴挸顭烽弻娑樼暆閳ь剟宕戝☉姘变笉鐎规洖娲﹂崰鎰偓骞垮劚椤︻垶寮?
            Map<String, Object> values;
            try {
                values = collectFuture.get(
                        collectorProperties.getScheduler().getCollectTimeoutMs(),
                        TimeUnit.MILLISECONDS
                );
            } catch (TimeoutException e) {
                collectFuture.cancel(true);
                log.warn("Device {} batch collection timed out and underlying task was cancelled", deviceId);
                return;
            } catch (InterruptedException e) {
                collectFuture.cancel(true);
                Thread.currentThread().interrupt();
                log.warn("Device {} batch collection interrupted and underlying task was cancelled", deviceId);
                return;
            }

            if (!values.isEmpty()) {
                // 闂備浇顕х€涒晠顢欓弽顓炵獥闁哄稁鍘肩壕鍦喐閻楀牆淇柡浣告閺岋綁骞囬浣瑰創闂佸搫顑勭欢姘跺蓟閿濆憘鏃堝焵椤掑嫭鍋嬮柛鈩冪懅閻牓鏌ㄩ弴妤€浜惧銈庡弨濞夋洟骞戦崟顖氫紶闁告洖鍚€缁辨垿姊?
                CompletableFuture.runAsync(() -> {
                    processCollectedData(deviceId, points, values);
                }, dataProcessorPool);

                success = true;
            }

        } catch (Exception e) {
            log.error("Device {} batch collection failed", deviceId, e);
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;

            // 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ɑ褰冮顐ょ棯閹佸仮闁哄矉缍佸顕€鍩€椤掆偓椤啴宕稿Δ鈧憴锕傛煃?
            if (success) {
                collectionStatistics.collectionSuccess(deviceId, executionTime);
                performanceMonitor.recordBatchSuccess(deviceId, points.size(), executionTime);
            } else {
                collectionStatistics.collectionFailed(deviceId);
                performanceMonitor.recordBatchFailure(deviceId);
            }

            // 闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁瑰鍋涢婊呯磼娴ｅ嘲宓嗛柡宀嬬秮婵℃瓕顦查柡瀣〒閳ь剚顔栭崳顕€宕戦崨顓х劷闊洦绋戠粈鍫㈡喐韫囨稒鍋橀柕澶涘缁♀偓缂佸墽澧楄彜闁稿鎹囧畷妯好圭€ｎ亙澹曢悷婊呭鐢宕愮捄渚唵闁兼悂娼ф慨鍥╃磼閻橆喖鍔ら柟鍙夋倐楠炲鏁傜悰鈥充壕濞撴埃鍋撴鐐差儔閺佸啴鍩€椤掑倻灏电€广儱顦伴悡鏇熴亜閹板墎绋荤紒鈧崘顔界叆婵炴垶鑹鹃弸娑氣偓娈垮枛閻栧ジ宕洪埄鍐╁闁荤喓澧楅悘搴ㄦ⒒娴ｅ憡鎯堟俊顐ｇ箖缁傚秹宕奸弴鐐寸€悗骞垮劚閹虫劙寮抽崱娑欑厱闁哄洦锚婵＄厧霉濠婂牏鐣洪柡灞剧〒娴狅箓鎮欓鍌涱吇缂傚倷绀侀ˇ顖炲Χ閹间礁钃熸繛鎴欏灩缁狅絾绻濋棃娑氬ⅱ妞ゆ柨娲鍝劽洪懡銈呭弗闂佹悶鍔忓▔娑㈡偩閻戣棄唯闁挎棁妫勯崝鍛存⒑閹稿海绠撴繛璇х畵瀵櫕瀵肩€涙ǚ鎷洪梻渚囧亝缁嬫挾绮婇柨瀣ㄤ簻妞ゆ劑鍩勫Ο鈧銈冨灪濞茬喎鐣峰Δ鍛亗閹艰揪绲块悰顕€鏌ｉ悢鍝ョ煀缂佸缍婇獮?
            if (executionTime > 100) {  // 闂傚倷鑳堕崕鐢稿礈濠靛牊鏆滈柟鐑橆殔缁犵娀鏌熼幑鎰厫鐎?00ms
                adjustBatchSize(deviceId, -10);  // 闂傚倸鍊风粈渚€骞夐敓鐘插瀭闁汇垹鐏氬畷鏌ユ煙閹殿喖顣奸柛?0%闂傚倸鍊烽悞锕傛儑瑜版帒绀夌€光偓閳ь剟鍩€椤掍礁鍤柛妯兼櫕缁晠鎮㈤悡搴ｎ唺闂佺懓鐡ㄥ褰掓倵婵犳碍鈷戦柣鎰閸斻倝鏌涚€ｃ劌濡界紒鍌氱Ч閺佹劖寰勭€ｎ亖鍋?
            } else if (executionTime < 20) {  // 闂傚倸鍊风粈浣革耿闁秵鍋￠柟鎯版楠炪垽鏌嶉崫鍕偓褰掑级閹间焦鐓熼幖杈剧磿娴犳稒绻濋姀鈽呰€块柟顔ㄥ洤骞㈡繛鎴烆焽椤?
                adjustBatchSize(deviceId, 5);   // 濠电姷鏁告慨顓㈠箯閸愵喖宸濇い鎾寸箘閹规洖鈹?%闂傚倸鍊烽悞锕傛儑瑜版帒绀夌€光偓閳ь剟鍩€椤掍礁鍤柛妯兼櫕缁晠鎮㈤悡搴ｎ唺闂佺懓鐡ㄥ褰掓倵婵犳碍鈷戦柣鎰閸斻倝鏌涚€ｃ劌濡界紒鍌氱Ч閺佹劖寰勭€ｎ亖鍋?
            }
        }
    }

    /**
     * 闂傚倸鍊烽懗鍫曞储瑜旈妴鍐╂償閵忋埄娲稿┑鐘诧工鐎氼參宕ｈ箛娑欑厓闁告繂瀚崳褰掓偡濞嗘瑩妾柕鍥у瀵粙濡搁妸銉闁荤偞姘ㄩ崰鏍ь潖濞差亜鎹舵い鎾楀嫭鐦滈梻浣侯焾椤戝棝骞戦崶顒€钃熼柨鐔哄Т闁卞洦绻濋崹顐㈠濮濆洦淇婇悙顏勨偓鏍ь潖閻熸噴鍝勎熸笟顖氭?
     */
    private void autoStartAllDevices() {
        try {
            log.info("Starting all devices automatically");
            startAllDevices();
            log.info("Automatic startup of all devices completed");
        } catch (Exception e) {
            log.error("Automatic startup of all devices failed", e);
        }
    }

    /**
     * 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚弳鐐测攽椤栨哎鍋㈤柡宀€鍠栭、娑㈠幢濡や焦鎷遍柡宥忕節濮婂宕掑▎鎴濆閻熸粍婢橀崯顐ゅ弲濡炪倖鎸鹃崑鎰板几閺嶎厽鐓ラ柡鍥╁仜閳ь剙缍婂鏌ヮ敆閸曨剛鍘卞銈嗗姂閸婃洟寮搁弬娆剧唵闁荤喖鍋婇崕鏃堟煟閹垮啫浜扮€规洘鍎奸ˇ鏌ユ煛閸℃ɑ绀堢紒杈ㄥ笚椤垿寮借缁秹鎮楃憴鍕濠电偐鍋撻悗娈垮枙缁瑦淇婇幖浣规櫆闁诡垎鍐吋婵犵數濮烽弫鎼佸磻濞戔懞鍥级濡灚妞介崺锟犲礃閳哄﹥缍?
     */
    public boolean startDevice(String deviceId) {
        scheduleLock.lock();
        try {
            DeviceScheduleInfo scheduleInfo = deviceScheduleInfo.get(deviceId);
            if (scheduleInfo != null && scheduleInfo.isRunning()) {
                log.warn("Device {} collection is already running", deviceId);
                return false;
            }

            // 1. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閵忊剝鐝曟繝娈垮灠閵堟悂寮婚悢纰辨晬闁糕剝顨嗗﹢浼村冀閿熺姵鈷掗柛灞剧懅閸斿秵绻濋姀鈽嗙劷缂侇喗妫冨畷濂稿即閻旇渹鐥?
            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo == null) {
                log.error("Device {} configuration does not exist", deviceId);
                return false;
            }

            // 2. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閵忊剝鐝曟繝娈垮灠閵堟悂寮婚悢纰辨晬闁糕剝顨嗗﹢浼村冀閿熺姵鈷掑ù锝呮贡濠€浠嬫煕閺傝法鐏遍柍褜鍓氶惌顕€宕￠崘鑼殾闁惧浚鍋勯閬嶆倵濞戞姘跺箰閸涘瓨鈷戠紒顖涙礀婢ц尙绱掔€ｎ偄娴柡浣哥Ч閹垽宕楃亸鏍ㄥ闂備礁鎲℃笟妤呭窗濡ゅ惤澶愬冀椤愩倗锛滈柡澶婄墑閸斿酣骞婇崟顓犵＜闁稿本绋戠粭姘舵煙椤栨稒顥堝┑陇鍩栭幆鏃堝焺閸愨晝褰┑鐘垫暩婵即宕归悡搴樻灃婵炴垶鈼ゅú顏嶆晣闁靛繒濮撮崑宥夋⒑闂堟稓澧曟繛灞傚妿婢规洟鎮欓鍙ョ盎闂佸搫鍟ú锕偹夋径鎰厽闊洦妫忓▓鏃€銇勯锝庢當闁宠棄顦埢搴∥熼悡搴⌒┑锛勫亼閸婃牠骞愰幖浣测偓锕€鐣￠幍顔芥闂佽澹嗘晶妤呭吹瀹€鍕厸濠㈣泛瀛╃涵鑸点亜閺傛寧顥㈡慨濠冩そ瀹曘劍绻濋崟顒€娅楅梻浣虹帛椤ㄥ繘宕㈤幆顬?
            // 闂傚倸鍊烽悞锕傛儑瑜版帒鍨傜痪顓炴噷娴滅懓顭跨捄铏圭劮濠?collectionConfig 闂備浇顕у锕傦綖婢舵劖鍋ら柡鍥╁С閻掑﹥銇勮箛鎾跺⒊缂傚秵鐗犻弻銊╁即閻愭祴鍋撻崫銉х焼闁糕剝绋掗悡鐔镐繆椤栨繂浜归悽顖涚洴閺岋絽鈹戦崼婵囩亪闂佸搫琚崝宀勫煡婢跺á鐔兼嚃閳轰礁袨缂傚倸鍊烽懗鍓佸垝椤栫偞鍋嬮柣妯款嚙閽冪喓鈧厜鍋撻柍褜鍓氱粋鎺楁晝閸屾稑浜楅柟鐓庣摠钃遍柣搴°偢濮婄粯鎷呯憴鍕╀户濠电偟鍘у鈥崇暦濠靛绠ｉ柣妯诲絻閻忓﹤顪冮妶鍡欏缂侇喖绉甸崚濠冪附閸涘﹦鍘梺鍓插亝缁诲秴危閸涘﹦绠鹃柛娑卞枛閸濈儤鎱ㄦ繝鍌ょ吋鐎规洖宕灃濞撴艾娲﹂敍鍡樼節绾版ɑ顫婇柛瀣椤洭鍨鹃幇浣圭稁?

            // 3. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旂厧顫╅梺鍝勵儎缁舵岸寮婚敐鍛傛棃鍩€椤掑嫭鍋嬮柛鈩冪懅閻牓鏌ㄩ弴鐐测偓褰掓偂?
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            if (dataPoints.isEmpty()) {
                log.warn("Device {} has no configured data points", deviceId);
                return false;
            }
            
            // 4. 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼磼閳ь剙鐣濋崟顒傚幐閻庡箍鍎遍崯顐ｄ繆閼恒儳绠鹃柛顐ｇ箘閸╋綁鏌″畝鈧崰鎾诲箯閻樿鐏抽柧蹇ｅ亞娴滈箖姊绘担渚劸妞ゆ垶鍨圭槐鐐寸瑹閳ь剟鐛崱娑樼妞ゆ棁鍋愰ˇ鏉款渻閵堝棙鐓ラ柛姘儔椤㈡瑩宕堕浣叉嫽婵炴挻鑹惧ú銈嗙濠婂牊鐓曞┑鐘插€归幉鎼佹煠濞差亙鎲剧€殿喖顭锋俊鐑芥晜閹冪瑩闂傚倷鐒﹂幃鍫曞磿濞差亜绀堟慨妯垮煐閸ゅ啴鏌ｅΟ鑲╁笡闁抽攱鍨块弻鈩冨緞鎼淬垻銆婇柤鍙夌墵濮?
            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                for (DataPoint dataPoint : dataPoints) {
                    AdaptiveCollectionUtil.initDataPointAdaptiveConfig(dataPoint);
                }
            }

            // 4. 婵犵數濮烽弫鎼佸磻濞戔懞鍥敇閵忕姷顦悗鍏夊亾闁告洦鍋夐崺鐐烘⒑娴兼瑧鍒伴柡鍫墯閸掑﹥绺介崨濠勫幍濡炪倖鐗曞Λ妤呭嫉椤掍胶澧?
            try {
                collectionManager.registerDevice(deviceInfo);
            } catch (Exception e) {
                log.debug("Device {} was already registered", deviceId);
            }

            // 5. 闂傚倷绀侀幖顐λ囬锕€鐤炬繝闈涱儏绾惧鏌ｉ幇顒備粵闁哄棙绮撻幃妤呮濞戞瑥鏆堟繝娈垮灠閵堟悂寮婚悢纰辨晬闁糕剝顨嗗﹢浼村冀?
            if (!connectDevice(deviceId)) {
                log.error("Device {} connect failed during startup", deviceId);
                return false;
            }

            // 6. 闂傚倸鍊风粈渚€骞栭锕€绠犳俊顖濆亹绾捐姤鎱ㄥΟ鎸庣【缂佲偓閸屾稒鍙忔俊鐐额嚙娴滈箖鎮楃憴鍕鐎光偓缁嬭法鏆﹂柛妤冨亹閺嬪酣鏌熺€电啸闁告ɑ鍨垮缁樻媴閸涘﹤鏆堢紓渚囧枛閻倿骞嗙仦鍓х懝闁逞屽墴楠炲棝宕橀钘変簻闂佸憡绋戦敃銈夊礉閸涘瓨鈷戦柟绋垮椤ュ棝鏌涚€ｎ偄濮夌紒顔肩墦瀹曠喖顢涘☉姘箰闁诲骸绠嶉崕鍗灻归崒鐐村€块柛鎾楀懐锛滈梺鎯х箳閹虫挻绂嶆ィ鍐╁€甸柣銏ゆ涧鐢爼鎽堕敐澶嬬厱婵犻潧妫楅鎾煕閵堝棛鎳囨慨?
            scheduleDevicePoints(deviceId, dataPoints);

            //闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鎳岄埀顒€鍟村畷銊╁级閹存繃鍎俊鐐€栭幐鍫曞垂閸︻厾涓嶉柕澹嫬鏋戦梺纭呮彧鐠愮喖鍩€椤戣法绐旀鐐差儔閹晠宕樺顔瑰亾濞戙垺鈷戦柣鎰閸旀岸鏌涢悢閿嬪仴闁?
            collectionManager.rebuildReadPlans(deviceId,dataPoints);

            // 7. 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ê寮舵径鍕煕鐎ｎ偄濮嶉柡灞诲€濆畷顐﹀Ψ椤旇姤鐦滈梻?
            deviceScheduleInfo.put(deviceId, new DeviceScheduleInfo(deviceId, true));
            collectionStatistics.startCollection(deviceId, dataPoints.size());

            collectionServiceHealthTracker.markDeviceStarted(deviceId);

            log.info("闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇炲€哥壕鍧楁煙鐠哄搫顥?{} 闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰电厑濞差亶鏁囬柣鎰ㄦ櫆閻忓啴姊洪幐搴㈢闁稿﹤鎽滈幉鎾晜婵劒绨婚梺鍝勭Р閸斿繑绔熷Ο鍏煎仏闁绘柨鍚嬮埛鎴︽煟閻旂顥嬮柟鐣屽█閺屾盯寮埀顒€煤濮椻偓楠炲牓濡搁妷銏☆潔闂侀潧绻嗛崜婵嗏枍閺嶎厽鍊甸柛蹇擃槸娴滈箖姊洪柅鐐茶嫰婢у鈧娲橀崹鍧楀极閹邦厼绶炲┑鐘插暟閳? {}", deviceId, dataPoints.size());
            return true;

        } catch (Exception e) {
            log.error("Failed to start device collection for {}", deviceId, e);
            return false;
        } finally {
            scheduleLock.unlock();
        }
    }

    /**
     * 闂傚倸鍊风粈渚€骞栭锕€绠犳俊顖濆亹绾捐姤鎱ㄥΟ鎸庣【缂佲偓閸屾稐绻嗘い鏍ㄨ壘閹垿鎮楀鐓庡⒋闁哄本鐩獮鍥煛娴ｅ壊鐎虫俊鐐€栭弻銊╂晝椤忓牆绠栭悷娆忓婵挳鏌ｉ悢鍛婄凡濠殿喓鍨归—鍐Χ韫囨挾妲ｉ梺鍛婃尵閸犳牠鐛崘銊㈡瀻闁规儳顕崝闈涒攽閻愭潙鐏︽慨妯稿妼閻☆參姊婚崒娆戝妽閻庣瑳鍛煓闁硅揪闄勯崑瀣攽閻樺弶澶勯柛瀣儔閹宕烽鐐愩儵鏌ｉ妶鍌氫壕闂傚倷绀侀幉锟犲礈椤掑嫬宸濇い鎾跺枔娴滅兘姊婚崒娆戭槮闁硅绻濋幃褍螖閸涱喖浜卞┑鐘诧工閻楀﹪宕戦埡鍛仯闁搞儯鍔庨妶鎾煛鐎ｎ偆澧甸柡宀嬬節瀹曞爼寮甸悽鍨櫦缂傚倷璁查崑?
     */
    private void scheduleDevicePoints(String deviceId, List<DataPoint> points) {
        List<DeviceBatchTask> batchTasks = deviceBatchPlanner.plan(
                deviceId,
                points,
                TIME_SLICE_COUNT.get(),
                performanceMonitor);
        for (DeviceBatchTask batchTask : batchTasks) {
            List<DeviceBatchTask> tasks = timeSliceTasks.get(batchTask.timeSliceIndex);
            if (tasks != null) {
                tasks.add(batchTask);
            }
        }
    }

    /**
     * 闂傚倷绀侀幖顐λ囬锕€鐤炬繝闈涱儏绾惧鏌ｉ幇顒備粵闁哄棙绮撻幃妤呮濞戞瑥鏆堟繝娈垮灠閵堟悂寮婚悢纰辨晬闁糕剝顨嗗﹢浼村冀?
     */
    private boolean connectDevice(String deviceId) {
        try {
            collectionManager.connectDevice(deviceId);
            configManager.getDataPointsAndAdaptiveConfig(deviceId);
            return true;
        } catch (Exception e) {
            log.error("Device {} connect failed", deviceId, e);
            return false;
        }
    }

    /**
     * 闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰典簽閻捇鏌ｉ姀銏╃劸闁藉啰鍠庨埞鎴︽偐閹绘帗娈叉繛瀛樺殠閸婃繈寮婚敐澶婄疀妞ゆ挾鍠撶粙鍥ㄧ節閵忥絾纭剧紒澶婄秺楠炲啰鎲撮崟顒€顎撻梺鑽ゅ枑濠㈡ɑ鎱ㄩ姀鐙€娓?
     */
    private boolean reconnectDevice(String deviceId) {
        try {
            collectionManager.reconnectDevice(deviceId);
            return true;
        } catch (Exception e) {
            log.error("Device {} reconnect failed", deviceId, e);
            return false;
        }
    }

    /**
     * 闂傚倸鍊烽懗鍫曗€﹂崼銉晞闁糕剝鐟ラ崹婵堚偓骞垮劚椤︿粙寮崱妯肩闁瑰鍋熼。鎻掆攽椤栨哎鍋㈤柡宀€鍠栭、娑㈠幢濡や焦鎷遍柡宥忕節濮婂宕掑▎鎴濆閻熸粍婢橀崯顐ゅ弲濡炪倖鎸鹃崑鎰板几?
     */
    public boolean stopDevice(String deviceId) {
        scheduleLock.lock();
        try {
            // 1. 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛悗鐢电《閸嬫挸鈽夐幒鎾寸彅濡炪値浜滈崯鏉戠暦閹烘鍊风紒顔款潐鐎氫粙姊虹拠鍙夋崳闁轰焦鎮傞垾锕傚醇閵夛絺鍋撻崒婊勫珰閻熶椒绀佺紞濠囧箖閳哄倻鐟规い鏍ㄧ椤斿嫬鈹戦悩鎰佸晱闁哥姵宀稿畷浼村箛椤斿墽鐓撴繝銏ｅ煐閸旓箓寮崶顒佺厽婵☆垳锛氬璺虹柧闁绘ê妯婂?
            for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
                tasks.removeIf(task -> task.deviceId.equals(deviceId));
            }

            // 2. 闂傚倸鍊风粈渚€骞栭锕€纾婚柛鈩冪☉閸屻劑鏌ゅù瀣珕妞ゎ偅娲熼弻鐔衡偓鐢殿焾鏍℃繝娈垮灠閵堟悂寮婚悢纰辨晬闁糕剝顨嗗﹢浼村冀閿熺姵鈷戦柛婵嗗濡叉挳鏌￠崨顔剧疄鐎规洘鍨块幃娆撳垂椤愶絾顔?
            try {
                collectionManager.disconnectDevice(deviceId);
            } catch (Exception e) {
                log.warn("Device {} disconnect failed", deviceId, e);
            }

            // 3. 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ɑ褰冮顏堟倵濮樼厧澧撮柡灞剧洴楠炲洭鍩℃担鍓茬€虫俊鐐€栭弻銊╂晝閵堝牆绲归梻浣规偠閸庤崵寰婇懞銉︽珷闁圭粯宕?
            deviceScheduleInfo.remove(deviceId);

            // 4. 闂傚倸鍊烽懗鍫曗€﹂崼銉晞闁糕剝鐟ラ崹婵堚偓骞垮劚椤︿粙寮崱妯肩闁瑰鍋為惃鎴犵棯閹佸仮闁哄矉缍佸顕€鍩€椤掆偓椤啴宕稿Δ鈧憴锕傛煃?
            collectionStatistics.stopCollection(deviceId);
            collectionServiceHealthTracker.markDeviceStopped(deviceId);

            log.info("Device {} collection stopped", deviceId);
            return true;

        } catch (Exception e) {
            log.error("Failed to stop device collection for {}", deviceId, e);
            return false;
        } finally {
            scheduleLock.unlock();
        }
    }

    /**
     * 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼閻橆喖鍔﹂柡灞界Х椤т線鏌涢幘璺烘瀻妞ゆ洩缍佸畷褰掝敃閵忋垻鐛梺鐟板悑閹矂宕伴弽顓炵柧妞ゆ挾鍠撶弧鈧?
     */
    public void startAllDevices() {
        List<String> deviceIds = configManager.getAllDeviceIds();
        log.info("Starting collection for all devices, total {} devices", deviceIds.size());

        int successCount = 0;
        int failCount = 0;

        for (String deviceId : deviceIds) {
            try {
                DeviceContext context = configManager.getDeviceContext(deviceId);
                if (context != null
                        && context.getDeviceInfo() != null
                        && context.getConnectionConfig() != null) {
                    if (startDevice(deviceId)) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }
            } catch (Exception e) {
                log.error("Failed to start device {}", deviceId, e);
                failCount++;
            }
        }

        log.info("闂傚倸鍊风粈浣革耿闁秲鈧倹绂掔€ｎ亞锛涢梺鐟板⒔缁垶鎮″☉銏＄厱妞ゆ劧绲跨粻銉︿繆閹绘帒鎮戦柕鍥у瀵噣鍩€椤掆偓鐓ゆ俊顖欒閸ゆ鏌涢弴銊ョ仩闁绘劕锕﹂幉鍛婃償閵娿儳锛熷┑鐐叉閹稿鎮￠弴銏＄厸闁稿本绻嶉崵娆忊攽閳ョ偨鍋㈤柡宀嬬秮閺佹劖鎯旈垾鏂ユ嫬婵°倗濮烽崑娑㈩敄婢舵劗宓侀柛銉墮缁€鍫澝归敐鍕劅婵¤尙鍏樺缁樻媴閻戞ê娈岄梺瀹︽澘濮傜€规洘绻堝鎾偄缁嬪灝浼? {}闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸ゆ劖銇勯弽顐粶濡楀懘姊洪悷閭﹀殶濠殿喚鏁搁埀? {}", successCount, failCount);
    }

    /**
     * 闂傚倸鍊烽懗鍫曗€﹂崼銉晞闁糕剝鐟ラ崹婵堚偓骞垮劚椤︿粙寮崱妯肩闁瑰瓨鐟ラ悘鈺冪磼閻橆喖鍔﹂柡灞界Х椤т線鏌涢幘璺烘瀻妞ゆ洩缍佸畷褰掝敃閵忋垻鐛梺鐟板悑閹矂宕伴弽顓炵柧妞ゆ挾鍠撶弧鈧?
     */
    public void stopAllDevices() {
        List<String> runningDevices = new ArrayList<>(deviceScheduleInfo.keySet());
        log.info("Stopping collection for all devices, total {} running devices", runningDevices.size());

        for (String deviceId : runningDevices) {
            try {
                stopDevice(deviceId);
            } catch (Exception e) {
                log.error("Failed to stop device {}", deviceId, e);
            }
        }

        log.info("Stopped collection for all devices");
    }

    /**
     * 闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰典簽閻捇鏌ｉ姀銏╃劸闁藉啰鍠庨埞鎴︽偐閸欏鎮欑紓浣插亾濠㈣埖鍔栭悡鐔镐繆椤栨粌甯堕柛鏂款儑缁辨帗鎷呭畡鏉跨ギ闂佸搫鐬奸崰鎾诲窗婵犲伣鐔告姜閺夋妫滈梻鍌氬€风粈渚€骞栭锔藉亱闁糕剝铔嬮崶顒€绠紒娑橆儐閺呪晠姊洪崫鍕窛闁哥姴娴峰▎?
     */
    public void reloadAllDevices() {
        log.info("闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰典簽閻捇鏌ｉ姀銏╃劸闁藉啰鍠庨埞鎴︽偐閸欏鎮欑紓浣插亾濠㈣埖鍔栭悡鐔镐繆椤栨粌甯堕柛鏂款儑缁辨帗鎷呭畡鏉跨ギ闂佸搫鐬奸崰鎾诲窗婵犲伣鐔告姜閺夋妫滈梻鍌氬€风粈渚€骞栭锔藉亱闁糕剝铔嬮崶顒€绠紒娑橆儐閺呪晠姊洪崫鍕窛闁哥姴娴峰▎銏ゆ倷閻戞鍘遍梺闈涱樈閸犳牗鏅堕鐣岀闁告侗鍠栭崫鐑樻叏?..");
        stopAllDevices();
        timeSliceScheduler.schedule(this::startAllDevices, 2, TimeUnit.SECONDS);
    }

    /**
     * 濠电姷鏁告慨浼村垂閻撳簶鏋栨繛鎴炲焹閸嬫挸顫濋悡搴㈢彎濡ょ姷鍋涢崯顖滄崲濠靛鐐婇柕濞у啫绗撻梻鍌欑劍閹爼宕曞ú顏勭婵鍩栭崵鍐煟濡偐甯涢柣鎾寸☉闇夐柨婵嗘噺閹牊銇勯敐鍫濅汗闁?
     */
    private void processCollectedData(String deviceId, List<DataPoint> points,
                                      Map<String, Object> values) {
        collectedDataProcessor.process(deviceId, points, values, performanceMonitor);
    }

    /**
     * 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ê寮堕幖鎰版倵濮橆剦妯€闁哄苯绉烽¨渚€鏌涢幘瀛樼殤闁逞屽墰閺佹悂宕㈣閻忔帡鏌ｉ悩鍙夋悙婵☆垰锕よ灋婵°倕鎳忛埛鎺懨归敐鍥ㄥ殌缂佽尪宕电槐鎺旂磼濡搫顫掓繝纰夌磿閺佽鐣烽崼鏇炵厸闁逞屽墴閹?
     */
    private void updateOptimalBatchSize(String deviceId, int newSize) {
        // 闂傚倷绀侀幖顐λ囬锕€鐤炬繝濠傜墛閸嬶繝鏌嶉崫鍕櫣闂傚偆鍨堕弻锝夊箣閿濆棭妫勯梺娲诲幗椤ㄥ棝濡甸崟顖氬唨闁靛ě鍕珡缂傚倷闄嶉崝宥咁熆濡灝绲归梻浣规偠閸庮垶宕濇惔锝囦笉闁瑰墽绮悡鐔煎箳閹惰棄绀夐柟瀛樼箘閺嗭附鎱ㄥΟ鎸庣【缂佺媭鍨抽埀顒€鍘滈崑鎾斥攽閻樻彃鈧懓鈻嶅Δ鈧埞鎴︽偐椤旇偐浼囧┑鐐差槹缁嬫垹鍙呴梺鎸庢礀閸婃悂鎮為崹顐犱簻闁规惌鍘兼晶顔戒繆椤愶綆娈滅€规洏鍨婚埀顒勬涧閹芥粎澹曢挊澹濆綊鏁愰崨顔藉創闂佸搫顑嗛悷锕傚Φ閸曨垰绠婚柧蹇ｅ亜閺嗗牏绱?
        log.debug("闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇炲€哥壕鍧楁煙鐠哄搫顥?{} 闂傚倸鍊风粈浣虹礊婵犲偆鐒界憸蹇曟閻愬绡€闁搞儜鍥紬闂備胶绮崹鍫曟晪缂備礁澧庨崑鐔煎箟缁嬫鍚嬪璺侯儏閸擃厼顪冮妶鍡楃瑨闁稿﹤婀遍埀顒佸嚬閸撶喖寮诲☉銏犵疀闁靛闄勯悵鏇炩攽閻愯尙澧ｆい鏇嗗洤鐓? {}", deviceId, newSize);
    }

    /**
     * 闂傚倷娴囧畷鍨叏閹绢噮鏁勯柛娑欐綑閻ゎ喗銇勯弽顐粶婵鐓￠弻銊モ攽閸♀晜笑缂備胶濮伴崕鐢稿箖瀹勬壋鏋庨煫鍥ㄦ惄娴煎矂姊虹粙璺ㄦ噳闁告劕澧介崬鐢告⒑閸撴彃浜滄俊顐ｎ殜瀹曨垶寮堕崯?
     */
    private void adjustBatchSize(String deviceId, int percentChange) {
        // 闂傚倷绀侀幖顐λ囬锕€鐤炬繝濠傜墛閸嬶繝鏌嶉崫鍕櫣闂傚偆鍨伴—鍐偓锝庝簽瀹€鎼佹煕鐎ｎ偅宕岀€规洜鍏橀、姗€鎮欓悧鍫濈厱濠德板€楁慨鐑藉磻濞戞◤娲敇閻愬灚娈惧┑鐘绘涧椤戝懏鍎梻浣瑰濮婂骞婇幘瀵割洸闁诡垼鐏愯ぐ鎺撴櫜闁搞儯鍔嶉悵顏勨攽閻愯尙澧曢柣鏍с偢瀵鍩勯崘鈺侇€撻柣鐔哥懃鐎氼剟宕径鎰拺闂侇偆鍋涢懟顖涙櫠椤栨稐绻嗛柛娆忣槸缁椦勭箾?
        performanceMonitor.adjustBatchSize(deviceId, percentChange);
    }

    /**
     * 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼閻樺啿鐏╅柟鍙夋倐楠炲鏁冮埀顒傜矆閸屾稒鍙忔俊鐐额嚙娴滈箖姊虹拠鈥崇仩閻庢矮鍗抽悰顔锯偓锝庡枟閸婄兘妫呴顐㈠箻閻?
     */
    private void startPerformanceMonitoring() {
        timeSliceScheduler.scheduleAtFixedRate(
                () -> performanceMonitor.logStatistics(TIME_SLICE_INTERVAL),
                60, 60, TimeUnit.SECONDS
        );
    }
    
    /**
     * 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼閳ь剚寰勯幇顓犲帾婵犵數濮寸换妤呭触閸岀偞鐓曢悗锝庡亝鐏忎即鏌熷畡鐗堝櫧闁瑰弶鎸冲畷鐔煎垂椤愬秵绻堝濠氬磼濮橆兘鍋撻幖浣哥９闁归棿绶￠弫瀣亜閹捐泛鏋戞繛鍛█閺屸€愁吋鎼粹€茬凹闁诲孩鍑归崜鐔煎蓟濞戙垹绠涢柕濠忛檮閻濇洖鈹戦悙鑼ⅲ妞ゆ泦鍥ㄧ畳闂備胶绮湁閻犫偓閿曞倸鍚规繛鍡楃箚閺€?
     */
    private void startDynamicTimeSliceAdjustment() {
        int dynamicAdjustInterval = collectorProperties.getScheduler().getDynamicAdjustIntervalMs();
        timeSliceScheduler.scheduleAtFixedRate(this::adjustTimeSlicesDynamically, dynamicAdjustInterval, dynamicAdjustInterval, TimeUnit.MILLISECONDS);
        log.info("闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁硅揪绲剧涵鍫曟煕閺傝法校濞ｅ洤锕幃娆撳箵閹哄棗浜鹃柛顭戝櫘濞兼牠鏌ゆ慨鎰偓鎰板磻閹剧粯鍋ㄦ繛鍫ｆ硶閸旂顪冮妶蹇曞埌妞ゎ厾鍏橀獮鍐捶椤撴稑浜鹃柨婵嗙凹缁ㄥ鏌￠崱娆忎槐闁哄矉绻濆畷閬嶎敇閻樻彃鐓傛俊鐐€栭弻銊ф崲濮椻偓閻涱噣骞樼拠鑼唺闂佽鍎抽崯鎸庣珶閺冨牊鈷掑ù锝呮啞閸熺偞淇婇銏㈢闁诡喒鍓濆鍕沪缂併垺缍楁繝鐢靛█濞佳囨偋婵犲嫧鍋撳鐓庡⒋闁哄本鐩獮鍥Ω閿旂晫褰嗘繝鐢靛仜閻楀棝鈥﹀畡閭︽綎濠电姵鑹剧壕鍏肩箾閹寸偟鎳冮柛鐐差槸閳? {}ms", dynamicAdjustInterval);
    }
    
    /**
     * 闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁瑰鍋涢婊呯磼娴ｅ嘲宓嗛柡宀嬬秮婵℃瓕顦查柡瀣〒閳ь剚顔栭崳顕€宕戦崱娑樼劦妞ゆ帊鑳堕埊鏇㈡嫅鏉堛劊浜滈敎濠氬炊閵娿垺瀚介梻浣侯焾閺堫剟宕欒ぐ鎺戝惞闁绘柨顨庨悢鍡欐喐鎼淬劍鍋嬮柣妯烘▕閸ゆ洘銇勯幇鍓佸埌妞ゆ洟浜堕弻鈩冨緞鐎ｎ亞浠稿銈呴獜缁绘繂顫忓ú顏勫窛濠电姴瀚悡鈧梻浣规た閸樺ジ宕愬宀€浜遍梻浣侯潒閸曞灚鐣烽梺?
     */
    private void adjustTimeSlicesDynamically() {
        try {
            // 1. 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旇崵鏆楁繛瀛樼矊缂嶅﹪寮婚悢鐓庣畾鐟滃秹寮虫潏銊ｄ簻闁靛牆鎳忛崳鐣岀磼鏉堛劌娴い銏＄☉閳规垿宕卞Δ浣稿姃缂傚倸鍊风欢锟犲窗濞戞ǚ鏋嶉柨婵嗩槸缁狀垳鎲搁悧鍫濈瑲闁稿瀚槐鎾存媴鐠囷紕鍔烽梺?
            double cpuLoad = getSystemCpuLoad();
            int activeDevices = deviceScheduleInfo.size();
            long totalTasks = timeSliceTasks.values().stream().mapToInt(List::size).sum();
            
            // 2. 闂傚倷娴囧畷鍨叏瀹曞洦顐介柕鍫濇处椤洟鏌￠崶銉ョ仾闁稿鏅涢埞鎴︽偐鐎圭姴顥濆┑鈽嗗亝閿曘垽寮婚悢灏佹灁闁割煈鍠楅悘宥夋⒑娴兼瑧绉ù婊冪埣瀵鏁撻悩鑼槰闂侀潧臎閸愵亜骞楀┑鐘殿暯濡插懘宕戦崟顐劷闁跨喓濮寸粻鏍喐閺傝法鏆﹂柛顐ｆ礀鎯熼梺鎸庢⒒閻℃棃宕埀顒勬⒒?
            int newSliceCount = calculateOptimalSliceCount(activeDevices, totalTasks, cpuLoad);
            
            // 3. 闂傚倸鍊风粈渚€骞栭銈囩煋闁绘垶鏋荤紞鏍ь熆鐠虹尨鍔熼柡鍡愬€曢妴鎺戭潩閿濆懍澹曢柣搴㈩問閸犳岸寮繝姘槬闁逞屽墯閵囧嫰骞掗幋婵愪患濠碘槅鍋呴敃銏ゅ蓟閿濆憘鏃堝焵椤掑嫭鍋嬮柛鈩冪☉閻撴洟鏌￠崘銊у闁稿濮电换娑㈠箣閻愬棙鍨甸敃銏″鐎涙鍘遍柟鑹版彧缁辨洜绮婚懡銈傚亾鐟欏嫭绀冮柨鏇樺灩铻為柛鎰╁妷濡插牊绻涢崱妤佺濞寸厧鎳樺娲倻閳轰礁鈷夐柣銏╁灡鐢繝鐛繝鍛杸闁瑰灝鍟€靛矂姊虹粙璺ㄧ伇闁稿鐩畷姗€鍩€椤掑嫭鈷戦柟鑲╁仜閸旀潙霉濠婂啰鍩ｇ€规洦鍓熷畷濂稿即閻愮敻鐛撻梻浣稿暱閹碱偊宕愭繝姘ｂ偓鏍礂缁楄桨绨婚梺闈涚箚閳ь剙纾禒濂告⒑閻熼偊娈犻柛瀣工閻ｅ嘲顫滈埀顒勩€佸鈧幃?
            long avgExecution = performanceMonitor.getAverageTimeSliceExecution();
            boolean timeoutDetected = performanceMonitor.consumeTimeSliceTimeout();
            int tunedInterval = timeSliceTuner != null
                    ? timeSliceTuner.adjustInterval(TIME_SLICE_INTERVAL.get(), avgExecution, timeoutDetected)
                    : TIME_SLICE_INTERVAL.get();
            
            // 4. 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ê寮堕幖鎰磼椤愩垻效闁哄本鐩、鏇㈡晲閸℃瑯妲伴梻浣瑰缁嬫捇宕伴弽顓炶摕闁挎繂鎲橀弮鍫濈劦妞ゆ巻鍋撻悡銈嗐亜閹惧崬鐏╅柛灞诲姂閺屾洟宕煎┑鍥х獩缂?
            applyTimeSliceConfigUpdate(newSliceCount, tunedInterval);

            log.info("闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁瑰鍋涢婊呯磼娴ｅ嘲宓嗛柡宀嬬秮婵℃瓕顦查柡瀣〒閳ь剚顔栭崳顕€宕戦崱娑樼劦妞ゆ帊鑳堕埊鏇㈡嫅鏉堛劊浜滈敎濠氬炊閵娿垺瀚介梻浣侯焾閺堫剟宕欒ぐ鎺戝惞闁绘柨顨庨悢? 闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇炲€哥壕鍧楁煙鐠哄搫顥為柛銉墻閺佸棝鏌涢弴銊ヤ簼闁?{}, 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忓Δ锝呭枤閺佸鎲告惔銊ョ疄闁靛ň鏅滈崑鍕煟閹惧啿顒㈤柣?{}, CPU闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶鈺€绱?{}, 婵犲痉鏉库偓妤佹叏閻戣棄纾绘繛鎴欏灩閻ゎ噣鏌涢…鎴濇珮闁绘帊绮欓獮鏍庨鈧俊鑲╃磼閻橆喖鍔ら柟鍙夋倐楠炲鏁傜悰鈥充壕?{}ms, 闂傚倸鍊风粈渚€骞栭锔藉亱闁糕剝铔嬮崶褏鏆﹂柛銉㈡櫇閻撳姊洪崷顓℃闁哥姵鐗滈悮?{}, 闂傚倷娴囧畷鍨叏閹绢噮鏁勯柛娑欐綑閻ゎ喗銇勯弽顐粶婵鐓￠弻銊モ攽閸♀晜笑闁荤喐鐟辩粻鎾荤嵁閺嶃劍缍囬柛鎾楀啫鐓傛俊鐐€х徊浠嬧€﹂悜钘夎摕?{}, 闂傚倸鍊烽懗鍓佸垝椤栫偑鈧啴宕ㄩ鍏兼そ椤㈡﹢鎮欏鍡欐创鐎规洜顭堣灃濞达絽寮剁€?{}ms, 闂傚倷娴囧畷鍨叏閹绢噮鏁勯柛娑欐綑閻ゎ喗銇勯弽顐粶婵鐓￠弻銊モ攽閸℃ê鏅甸梺缁樻⒒閸樠囨倶瀹曞洠鍋撶憴鍕婵炲眰鍔戦妴?{}",
                    activeDevices,
                    totalTasks,
                    String.format("%.2f", cpuLoad),
                    avgExecution,
                    timeoutDetected,
                    newSliceCount,
                    tunedInterval,
                    timeSliceTuner != null ? timeSliceTuner.getMode() : "UNKNOWN");
        } catch (Exception e) {
            log.error("Dynamic time-slice tuning failed", e);
        }
    }
    
    /**
     * 闂傚倷娴囧畷鍨叏瀹曞洦顐介柕鍫濇处椤洟鏌￠崶銉ョ仾闁稿鏅涢埞鎴︽偐鐎圭姴顥濋柣搴㈣壘椤︾敻寮诲鍫闂佸憡鎸诲銊╁焵椤掑倹鏆╅柛妯犲棛浜界紓鍌氬€烽悞锕傛晝閳轰絼锝夘敍閻愮补鎷婚梺绋挎湰閼归箖鍩€椤掍焦鍊愭い銏″哺椤㈡﹢鎮㈤崨濠勫娇闂備礁鎼粙渚€宕㈡總绋垮嚑濞达綀顕氳ぐ鎺戠闁稿繗鍋愮粙鍥р攽?
     */
    private int calculateOptimalSliceCount(int activeDevices, long totalTasks, double cpuLoad) {
        // 闂傚倸鍊烽懗鍓佹兜閸洖鐤鹃柣鎰ゴ閺嬪秹鏌ㄥ┑鍡╂Ф闁逞屽厸缁舵艾鐣烽妸褉鍋撳☉娅亝绂嶉柆宥嗏拺闁硅偐鍋涢崝姗€鏌涢弬璺ㄐ㈤柍缁樻崌瀹曞ジ寮撮悢鍝勫汲婵犵數鍋為崹鍫曟偡瑜旈妴鍌炲蓟閵夛妇鍘藉┑掳鍊撻悞锔句焊閿旂瓔娈介柣鎰綑閻忓瓨銇勯姀锛勬噧闁宠閰ｉ獮鍡氼槼婵炲懏顨呴埞鎴︽偐椤旇偐浼囧┑鐐差槹缁嬫垹鍙呴梺鎸庢礀閸婄效閺屻儲鐓熼柡鍐ㄥ€哥敮鑸典繆閻愭壆鐭欓柟顔斤耿閹瑩骞撻幒鎾斥偓顖炴倵鐟欏嫭绀冮柣鎿勭節瀵鈽夐姀鐘愁棟闂佸憡绻傜€氼剟鍩涙径鎰拺闁告繂瀚ˉ鐘绘煕閻樿櫕宕屾鐐茬箳閳ь剨缍嗛崰妤呭疾椤掑嫭鐓曢柡鍥ュ妼楠炴鎱?
        int baseSlices = Math.max(1, Math.min(activeDevices / 5 + 1, collectorProperties.getScheduler().getMaxTimeSliceCount()));
        
        // 闂傚倸鍊风粈渚€骞栭銈囩煋闁绘垶鏋荤紞鏍ь熆鐠虹尨鍔熼柡鍡愬€曢埥澶愬箻閾忣偅鍟漊闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶鈺€绱崇憸鐗堝笒閻愬﹪鏌嶉崫鍕偓鎼侇敂閳哄懏鈷戦柛娑橈工婵偓闂佸搫鎳忕划宀勫煝?
        if (cpuLoad > 0.8) {
            // 濠电姴鐥夐弶搴撳亾閺囥垹纾圭憸鐗堝坊閳ь剨绠撳畷濂稿閵忋垻鍔堕梺璇插嚱缂嶅棝宕戦崨顔藉弿婵犲﹤鐗婇悡鐘绘煙椤撶喎绗掗柛鏃€绮嶉妵鍕唩闁告劕澧介崬鐢告偡濠婂啰绠荤€规洘鍨甸埥澶娾枎閹邦剙浼庨梻浣虹帛閸旀宕曢妶澶婄；閻庯綆鍠楅悡鏇㈡煏婢舵稓鍒板┑陇濮ょ换娑㈠醇濠靛牆鐓熼梺鍝勮閸斿矂鍩ユ径濞㈢喖鎳栭埡浣感熷┑鐘殿暜缁绘繄绮婚弽顓炵哗闂侇剙绉寸粻顖炴煕濞戝崬鏋熼柛搴ｅ枛閺屾洝绠涚€ｎ亞浠鹃悶姘卞仱濮婄粯鎷呴搹鐟扮濡炪們鍔岄幊搴ｅ弲闂佹寧绻傞ˇ顖滅不閺屻儲鐓忛煫鍥ь儏閳ь剚娲滅划鍫熷緞閹邦厾鍘撻梺瀹犳〃缁€渚€寮抽悙瀵哥?
            baseSlices = Math.min(collectorProperties.getScheduler().getMaxTimeSliceCount(), baseSlices + 2);
        } else if (cpuLoad < 0.3) {
            // 濠电姷鏁搁崑鐘诲箵椤忓棗绶ら柦妯侯樈濞尖晠鏌ㄩ弬鍨挃闁活厼妫濋幃妤呮晲鎼粹€茬凹婵犳鍠楃划鎾诲蓟閵堝绠掗柟鐑樺灥婵洖顪冮妵顖欑濞诧箓鎮￠弴銏＄厵閻庢稒顭囩粻妯肩磼閻樺崬宓嗛柡宀嬬秮閳ワ箓骞嬪┑鍫滄闂備浇顕栭崯顐﹀炊瑜忛崐鐐烘⒑闂堟侗鐓梻鍕椤㈡瑥顓兼径瀣ф嫼闂佸湱顭堝ù鐑藉煡婢舵劖鐓犳繛鑼额嚙閻忥妇鎹勯鐐寸厱闁逛即娼ч弸娑氱磼閳ь剚寰勯幇顓犲幗濠碘槅鍨靛▍锝夋晬瀹ュ棔绻嗛悘鐐插€告慨鍌炴煛瀹€瀣埌閾绘牠鏌嶈閸撴瑧鍙呴梺鎸庣箓椤︻垳绮婚弻銉︾厪闊洤顑呴埀顒佹礈缁牊寰勯幇顓犲帗闂佸疇妗ㄧ粈渚€寮抽悙瀵哥?
            baseSlices = Math.max(2, baseSlices - 1);
        }
        
        return baseSlices;
    }
    
    /**
     * 闂傚倷娴囧畷鍨叏瀹曞洦顐介柕鍫濇处椤洟鏌￠崶銉ョ仾闁稿鏅涢埞鎴︽偐鐎圭姴顥濋柣搴㈣壘椤︾敻寮诲鍫闂佸憡鎸诲銊╁焵椤掑倹鏆╅柛妯犲棛浜界紓鍌氬€烽悞锕傛晝閳轰絼锝夘敍閻愮补鎷婚梺绋挎湰閼归箖鍩€椤掍焦鍊愭い銏″哺椤㈡﹢鎮㈤崨濠勫娇闂備礁鎼粙渚€宕㈡禒瀣厱闁圭儤顨嗛悡娆戠磽娴ｉ潧鐏╅柡瀣枑閵?
     */
    /**
     * 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ê寮堕幖鎰磼椤愩垻效闁哄本鐩、鏇㈡晲閸℃瑯妲伴梻浣瑰缁嬫捇宕伴弽顓炶摕闁挎繂鎲橀弮鍫濈劦妞ゆ巻鍋撻悡銈嗐亜閹惧崬鐏╅柛灞诲姂閺屾洟宕煎┑鍥х獩缂?
     */
    private void applyTimeSliceConfigUpdate(int newSliceCount, int newSliceInterval) {
        int normalizedSliceCount = Math.max(1, newSliceCount);
        int normalizedSliceInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                newSliceInterval
        );

        scheduleLock.lock();
        try {
            int oldSliceCount = TIME_SLICE_COUNT.get();
            int oldSliceInterval = TIME_SLICE_INTERVAL.get();
            boolean sliceCountChanged = normalizedSliceCount != oldSliceCount;
            boolean intervalChanged = normalizedSliceInterval != oldSliceInterval;
            if (!sliceCountChanged && !intervalChanged) {
                return;
            }

            TIME_SLICE_COUNT.set(normalizedSliceCount);
            TIME_SLICE_INTERVAL.set(normalizedSliceInterval);
            if (sliceCountChanged) {
                rebuildTimeSliceAssignments();
            }
            startTimeSliceScheduling();
        } finally {
            scheduleLock.unlock();
        }
    }

    private void rebuildTimeSliceAssignments() {
        resetTimeSliceTaskBuckets(TIME_SLICE_COUNT.get());
        List<String> deviceIds = new ArrayList<>(deviceScheduleInfo.keySet());
        for (String deviceId : deviceIds) {
            try {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                if (dataPoints == null || dataPoints.isEmpty()) {
                    log.warn("Device {} has no points during time-slice reassignment, skipping", deviceId);
                    continue;
                }
                scheduleDevicePoints(deviceId, dataPoints);
            } catch (Exception e) {
                log.error("Failed to rebuild time-slice assignments for device {}", deviceId, e);
            }
        }
    }

    private void updateTimeSliceConfig(int newSliceCount, int newSliceInterval) {
        applyTimeSliceConfigUpdate(newSliceCount, newSliceInterval);
    }
    
    /**
     * 闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰典簽閻捇鏌ｉ姀銏╃劸闁藉啰鍠庨埞鎴︽偐閹绘帩浠鹃柣搴㈠嚬閸撶喖寮诲☉銏犵疀闁宠桨绀侀‖鍫濐渻閵堝棙鐓ラ柨鏇ㄤ邯瀵鎮㈤崫鍕€冲┑鈽嗗灥濡椼劍绔熼弴銏♀拻濞达絽鎲￠幆鍫ユ煟濡も偓濡繆妫熼梺鐟板閻℃棃寮繝鍥ㄧ厸闁搞儮鏅涢弸搴ｇ磼?
     */
    private void rescheduleAllDevices() {
        rebuildTimeSliceAssignments();
    }
    
    /**
     * 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閵忊剝鐝旈柣搴＄仛閻擄繝寮婚妶鍡樺弿闁归偊鍏橀崑鎾澄旈崨顓熸К闂佺粯娲橀埞濂ら梻鍌欐祰瀹曞灚鎱ㄩ悽绋跨婵°倕鎳庣粣妤呮煛閸モ晙绱?
     */
    private double getSystemCpuLoad() {
        // 缂傚倸鍊搁崐鐑芥嚄閼稿灚鍙忔い鎾卞灩绾惧鏌熼崜褏甯涢柣鎾存礋閺屸€愁吋閸愩劌顬嬫繝鈷€灞奸偗闁哄矉缍佹俊鍫曞川椤撗傜磾闂備浇顕栭崰鏍椤撱垹鏋佹い鏇楀亾妞ゃ垺鐟╁畷婊嗩檨婵¤尙鍏樺娲传閸曞灚孝闂佸搫鎳忕划鎾愁嚕婵犳艾惟闁靛鍟钘夘嚕娴犲惟鐟滃寮搁弽顓熲拺闂侇偆鍋涢懟顖涙櫠椤栨粎纾奸悗锝庝憾濡偓濡炪們鍨哄Λ鍐ㄧ暦閻旂⒈鏁囬柣鎰摠濞堛垺绻濋悽闈涗粶闁宦板妿閸掓帒鐣濋埀顒勫焵椤掍胶鈻撻柡鍛洴閳ユ棃宕橀鍛／闂侀潧顭粻鎴犲椤栫偞鈷戦柛娑橈工婵箑霉濠婂嫮鐭婇柍缁樻崌閹晠鎮介悽纰夌床闂佽鍑界紞鍡樼閸洖纾块柟鎵閻撴盯鏌涢埄鍐噧闁哄鐩弻鐔碱敍濮橆剚娈婚悗瑙勬礈閸犳牠銆侀弮鍫濈妞ゆ劧绲介惃銊х磽閸屾艾鈧兘鎳楅懜鍨弿闁汇垺鎮堕埀顒€鍟村畷銊╁级閹寸姴濮?
        int activeThreads = asyncCollectorPool.getActiveCount() + dataProcessorPool.getActiveCount();
        int maxThreads = asyncCollectorPool.getMaximumPoolSize() + dataProcessorPool.getMaximumPoolSize();
        return Math.min(1.0, (double) activeThreads / maxThreads);
    }

    /**
     * 闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁瑰鍋熺粻鎯р攽閻樿弓杩规繛鎴欏灩缁犵粯銇勯弮鍥т汗妞ゆ挸銈搁幃宄邦煥閸曨偆浼岄梺闈涙缁舵岸鐛€ｎ喗鏅濋柍褜鍓熷?
     */
    private void shutdownExecutor(String name, ExecutorService executor) {
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("{} shut down", name);
        }
    }

    /**
     * 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閵忊剝鐝曟繝娈垮灠閵堟悂寮婚悢纰辨晬闁糕剝顨嗗﹢浼村冀閿熺姵鈷戦柤濮愬€曞瓭濡炪倖鍨甸幊妯虹暦椤栫儐鏁冮柕鍫濆€告禍楣冩偡濞嗗繐顏╅柍缁樻礋閺岀喖顢欑憴鍕彋闂佺娅曠划搴ㄥ窗婵犲伣鐔告姜閺夋妫?
     */
    public Map<String, Object> getDeviceScheduleStatus(String deviceId) {
        Map<String, Object> status = new HashMap<>();
        status.put("deviceId", deviceId);

        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        status.put("isRunning", info != null && info.isRunning());
        status.put("connected", collectionManager.isDeviceConnected(deviceId));
        status.put("statistics", collectionStatistics.getDeviceStatistics(deviceId));
        status.put("performance", performanceMonitor.getDevicePerformance(deviceId));

        return status;
    }

    /**
     * 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閿濆懎顬夋繛瀛樺殠閸婃繈寮婚悢鍏肩劷闁挎洍鍋撻柣蹇涗憾閺岋繝鍩€椤掑嫭瀵犲璺烘椤秹姊洪悷鎵虎闁哥噥鍋婇悰顔嘉熼懡銈囶啎闂佸壊鍋嗛崰搴ㄦ倶閳哄懏鐓欑€瑰嫰鍋婇崕鏃€顨ラ悙鍙夘棥妞ぱ傜劍娣囧﹪鎳犻浣规瘓闂佸搫鏈惄顖炵嵁閹烘绠奸柛鎰ㄦ櫇閺嗩垶姊?
     */
    public List<String> getRunningDevices() {
        return deviceScheduleInfo.entrySet().stream()
                .filter(entry -> entry.getValue().isRunning())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎾翠繆閻愵亜鈧牕顫忛悷鎳婂搫螣娓氼垰娈梺鍛婃处閸ㄤ即鎮欐繝鍥ㄧ厪濠电偑鍊愰崑鎾绘煥閺囩偛鈧綊鎮￠弴鐔稿弿婵☆垰鎼埛鏃堟煟閿濆牓鍝虹紒缁樼箞閸┾偓妞ゆ帒瀚烽弫鍌炴煕濞戝崬鏋欐慨姗堢畵濮婇缚銇愰幒鎴滃枈闂佹悶鍔岄妶绋款嚕?
     */
    public boolean isDeviceRunning(String deviceId) {
        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        return info != null && info.isRunning();
    }

    /**
     * 闂傚倸鍊搁崐鐑芥倿閿曗偓椤灝螣閼测晝鐓嬮梺鍓插亝濞叉﹢宕戦鍫熺厱闁斥晛鍟伴埥澶愭煕濡や礁鈻曢柟顔筋殔閳藉鈻嶉搹顐㈢伌闁诡噯绻濋崺鈧い鎺嶈兌缁犻箖鎮楀☉娆樼劷闁活厼锕ラ妵鍕箣濠靛棭浠奸梺瀹狀嚙闁帮絾鎱ㄩ埀顒勬煏閸繃顥滈柛鎿冨櫍濮婃椽鎮烽幍顔芥喖缂備浇顕ч崯鏉戠暦?
     */
    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        String deviceId = event.getDeviceId();
        if ("local-delete".equals(event.getConfigType())) {
            if (deviceId != null && isDeviceRunning(deviceId)) {
                log.info("Device {} received local-delete config event, stopping collection", deviceId);
                stopDevice(deviceId);
            }
            return;
        }
        if (deviceId != null && isDeviceRunning(deviceId)) {
            log.info("Device {} received config update, scheduling restart", deviceId);
            ScheduledFuture<?> oldTask = pendingConfigRestartTasks.get(deviceId);
            if (oldTask != null && !oldTask.isDone()) {
                oldTask.cancel(false);
            }
            ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(() -> {
                stopDevice(deviceId);
                startDevice(deviceId);
                configManager.getDataPointsAndAdaptiveConfig(deviceId);
                pendingConfigRestartTasks.remove(deviceId);
            }, CONFIG_RESTART_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            pendingConfigRestartTasks.put(deviceId, restartTask);
        }
    }
}

