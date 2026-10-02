package com.voicecontrol.app

/** 生产与离线回放共用第一次点击：复查、选编号、至多派发一次；false绝不补点。 */
internal class NumberTapDispatcher {
    data class Result(val number: Int, val correction: NumberReviewPolicy.Correction?,
                      val rejection: String?, val dispatched: Boolean, val attempted: Boolean)
    private var lastReviewedUid: String? = null

    fun dispatch(textNumber: Int, text: String, audio: List<Pair<String, Float>>?,
                 request: NumberReviewContext.Request?, live: NumberReviewContext.Live,
                 supplementary: List<Pair<String, Float>>? = null,
                 pairAudio: List<Pair<String, Float>>? = null,
                 confirmation: List<Pair<String, Float>>? = null,
                 tap: (Int) -> Boolean): Result {
        val hasAudio = audio != null || supplementary != null || pairAudio != null
        val rejection = if (hasAudio) NumberReviewContext.rejection(request, live) else null
        if (hasAudio && rejection == null && live.uid == lastReviewedUid)
            return Result(textNumber, null, "already_dispatched", false, false)
        // 2026-10-01：补充头只能补旧头弃权，不能取消或改写已有数字判决。
        // 分段模型独立替换旧头会丢掉它已正确救回的编号，开发回放已证伪。
        var evidenceRejection: String? = null
        val correction = if (hasAudio && rejection == null) {
            val proposal = NumberReviewPolicy.correctBeforeTap(textNumber, live.snapshot!!.targets.size, true,
                audio.orEmpty(), NumberReviewPolicy.isCleanParse(text)) ?: supplementary?.takeIf {
                    // 补充模型的开发选择线；旧头原 .80/.95 不变。
                    it.firstOrNull()?.second?.let { p -> p.isFinite() && p in .99f..1f } == true
                }?.let {
                NumberReviewPolicy.correctBeforeTap(textNumber, live.snapshot.targets.size, true,
                    it, NumberReviewPolicy.isCleanParse(text))
            }
            if (proposal == null) {
                NumberPairReviewPolicy.correctBeforeTap(textNumber, text,
                    live.snapshot.targets.size, pairAudio)
            } else {
                evidenceRejection = NumberCorrectionGuard.rejection(proposal, confirmation, pairAudio,
                    NumberReviewPolicy.isCleanParse(text))
                // 严格4/10头可证明同一个改号提案达到原门槛；不许换成冲突的另一编号绕过拒绝。
                val strictPair = NumberPairReviewPolicy.correctBeforeTap(textNumber, text,
                    live.snapshot.targets.size, pairAudio)
                if (evidenceRejection == null || strictPair == proposal) {
                    evidenceRejection = null
                    proposal
                } else null
            }
        } else null
        val target = correction?.toNumber ?: textNumber
        if (hasAudio && rejection == null) lastReviewedUid = live.uid
        return Result(target, correction, rejection ?: evidenceRejection, tap(target), true)
    }
}
