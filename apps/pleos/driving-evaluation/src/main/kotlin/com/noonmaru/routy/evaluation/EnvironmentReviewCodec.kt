package com.noonmaru.routy.evaluation

object EnvironmentReviewCodec {
    fun summary(result:EnvironmentResult):JsonObject = mapOf("source" to result.source,"frames" to result.frames,"excluded" to result.excluded,
        "events" to result.events.map { event -> mapOf("kind" to event.kind,"startMs" to event.startMs,"endMs" to event.endMs,"detail" to event.detail) +
            (if(result.review==null) emptyMap() else mapOf("evidence" to event.evidence,"subjectId" to event.subjectId)) }) +
        (result.review?.let { review -> mapOf("review" to mapOf(
            "quality" to review.quality.map { mapOf("channel" to it.channel,"totalMs" to it.totalMs,"eligibleMs" to it.eligibleMs,"noOpportunityMs" to it.noOpportunityMs,"excludedMs" to it.excludedMs,"reasons" to it.reasons) },
            "issues" to review.issues.map { mapOf("startMs" to it.startMs,"endMs" to it.endMs,"reason" to it.reason.name) },
            "metrics" to review.metrics,"groups" to review.groups.map { mapOf("id" to it.id,"startMs" to it.startMs,"endMs" to it.endMs,"title" to it.title,"nextPractice" to it.nextPractice,"kinds" to it.kinds,"evidence" to it.evidence,"limited" to it.limited) }
        )) } ?: emptyMap())
}
