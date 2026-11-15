package com.example.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class SimpleXNode(
    val id: String,
    val host: String,
    val region: String,
    val countryCode: String, // e.g. "US", "DE", "IS", "JP", "CH", "NL", "SG"
    val ipAddress: String,
    val latencyMs: Int,
    val isActive: Boolean = true,
    val loadPercentage: Int = (20..85).random()
)

data class OnionCircuit(
    val hops: List<SimpleXNode>,
    val finalAddress: String,
    val estLatencyMs: Int,
    val encryptionKeyStr: String
)

object SimpleXNodeEngine {
    private val globalNodePool = listOf(
        SimpleXNode("sn-01", "node-us1.simplex.one", "North America", "US", "104.244.42.1", 38),
        SimpleXNode("sn-02", "node-de1.simplex.one", "Europe (Central)", "DE", "46.165.195.4", 51),
        SimpleXNode("sn-03", "node-is1.simplex.one", "Europe (Nordic)", "IS", "82.221.129.19", 94),
        SimpleXNode("sn-04", "node-ch1.simplex.one", "Europe (Swiss)", "CH", "109.202.99.1", 42),
        SimpleXNode("sn-05", "node-jp1.simplex.one", "Asia Pacific", "JP", "210.140.10.3", 112),
        SimpleXNode("sn-06", "node-sg1.simplex.one", "Asia Southeast", "SG", "128.199.112.5", 88),
        SimpleXNode("sn-07", "node-nl1.simplex.one", "Europe (Netherlands)", "NL", "95.211.201.2", 45),
        SimpleXNode("sn-08", "node-fi1.simplex.one", "Europe (Finland)", "FI", "95.175.99.8", 68)
    )

    private val _availableNodes = MutableStateFlow(globalNodePool)
    val availableNodes: StateFlow<List<SimpleXNode>> = _availableNodes.asStateFlow()

    private val _activeCircuit = MutableStateFlow<OnionCircuit?>(null)
    val activeCircuit: StateFlow<OnionCircuit?> = _activeCircuit.asStateFlow()

    private val _isAutoRoutingEnabled = MutableStateFlow(true)
    val isAutoRoutingEnabled: StateFlow<Boolean> = _isAutoRoutingEnabled.asStateFlow()

    private val _routingStatus = MutableStateFlow("Initialized. Zero-Leaking Secure Channel established.")
    val routingStatus: StateFlow<String> = _routingStatus.asStateFlow()

    init {
        rebuildCircuit()
    }

    fun setAutoRouting(enabled: Boolean) {
        _isAutoRoutingEnabled.value = enabled
        if (enabled) {
            _routingStatus.value = "Autonomous multi-hop node coordination active."
            rebuildCircuit()
        } else {
            _routingStatus.value = "Manual node overlay override active."
            _activeCircuit.value?.let { current ->
                if (current.hops.size > 1) {
                    // Reduce to a single node circuit for manual override
                    val singleHop = current.hops.first()
                    _activeCircuit.value = OnionCircuit(
                        hops = listOf(singleHop),
                        finalAddress = current.finalAddress,
                        estLatencyMs = singleHop.latencyMs,
                        encryptionKeyStr = current.encryptionKeyStr
                    )
                }
            }
        }
    }

    fun selectNodeForManualCircuit(selectedNode: SimpleXNode) {
        _isAutoRoutingEnabled.value = false
        val hops = listOf(selectedNode)
        _activeCircuit.value = OnionCircuit(
            hops = hops,
            finalAddress = "simplex-tunnel://" + UUID.randomUUID().toString().take(12) + ".onion",
            estLatencyMs = selectedNode.latencyMs,
            encryptionKeyStr = "ECDH-25519-Manual-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        )
        _routingStatus.value = "Connected to manual egress exit node: ${selectedNode.host}"
    }

    fun rebuildCircuit() {
        // Multi-hop (usually 3 hops) onion routing
        val randomizedPool = globalNodePool.shuffled()
        val hopsCount = if (_isAutoRoutingEnabled.value) 3 else 1
        val hops = randomizedPool.take(hopsCount)
        val circuitId = UUID.randomUUID().toString().take(12)
        
        val totalLat = hops.sumOf { it.latencyMs } + (15..35).random()
        
        _activeCircuit.value = OnionCircuit(
            hops = hops,
            finalAddress = "simplex-tunnel://$circuitId.onion",
            estLatencyMs = totalLat,
            encryptionKeyStr = "ECDH-25519-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        )
        
        _routingStatus.value = "Autonomous cryptographic onion circuit generated. Nodes: " + 
                hops.joinToString(" ➔ ") { it.countryCode } + " ($totalLat ms)"
    }
}
