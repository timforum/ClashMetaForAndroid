package com.github.kr328.clash

import com.github.kr328.clash.design.ProbTestIntervalsDesign
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive

class ProbTestIntervalsActivity : BaseActivity<ProbTestIntervalsDesign>() {
    override suspend fun main() {
        val design = ProbTestIntervalsDesign(
            this,
            ServiceStore(this),
        )

        setContentDesign(design)

        while (isActive) {
            events.receive()
        }
    }
}
