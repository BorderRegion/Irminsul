package com.github.quiltservertools.ledger.network.packet.receiver

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.commands.arguments.SearchParamArgument
import com.github.quiltservertools.ledger.database.DatabaseManager
import com.github.quiltservertools.ledger.network.Networking
import com.github.quiltservertools.ledger.network.packet.LedgerPacketTypes
import com.github.quiltservertools.ledger.network.packet.action.ActionS2CPacket
import com.github.quiltservertools.ledger.network.packet.response.ResponseCodes
import com.github.quiltservertools.ledger.network.packet.response.ResponseContent
import com.github.quiltservertools.ledger.network.packet.response.ResponseS2CPacket
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

data class SearchC2SPacket(val args: String, val pages: Int) : CustomPayload {

    override fun getId() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<SearchC2SPacket> {
        val ID: CustomPayload.Id<SearchC2SPacket> = CustomPayload.Id(LedgerPacketTypes.SEARCH.id)
        val CODEC: PacketCodec<PacketByteBuf, SearchC2SPacket> = CustomPayload.codecOf({ _, _ -> TODO() }, {
            SearchC2SPacket(it.readString(), it.readInt())
        })

        @Suppress("TooGenericExceptionCaught")
        override fun receive(payload: SearchC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            val sender = context.responseSender()
            if (!Permissions.check(player, "ledger.networking", CommandConsts.PERMISSION_LEVEL) ||
                !Permissions.check(player, "ledger.commands.search", CommandConsts.PERMISSION_LEVEL)
            ) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(
                        LedgerPacketTypes.SEARCH.id,
                        ResponseCodes.NO_PERMISSION.code
                    ),
                    sender
                )
                return
            }

            val source = player.commandSource

            val params = try {
                SearchParamArgument.get(payload.args, source)
            } catch (throwable: Throwable) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(LedgerPacketTypes.SEARCH.id, ResponseCodes.ERROR.code),
                    sender
                )
                return
            }
            val pages = payload.pages.coerceIn(1, Networking.MAX_NETWORK_RESULT_PAGES)

            ResponseS2CPacket.sendResponse(
                ResponseContent(LedgerPacketTypes.SEARCH.id, ResponseCodes.EXECUTING.code),
                sender
            )

            Ledger.launch {
                try {
                    Ledger.searchCache[source.name] = params
                    val results = DatabaseManager.searchActions(params, 1)
                    if (results.actions.isEmpty()) {
                        ResponseS2CPacket.sendResponse(
                            ResponseContent(LedgerPacketTypes.SEARCH.id, ResponseCodes.NO_RESULTS.code),
                            sender
                        )
                        return@launch
                    }

                    for (i in 1..minOf(pages, results.pages)) {
                        DatabaseManager.searchActions(results.searchParams, i).actions.forEach { action ->
                            sender.sendPacket(ActionS2CPacket(action))
                        }
                    }

                    ResponseS2CPacket.sendResponse(
                        ResponseContent(
                            LedgerPacketTypes.SEARCH.id,
                            ResponseCodes.COMPLETED.code
                        ),
                        sender
                    )
                } catch (throwable: Throwable) {
                    Ledger.logger.warn("Network Ledger search failed", throwable)
                    ResponseS2CPacket.sendResponse(
                        ResponseContent(LedgerPacketTypes.SEARCH.id, ResponseCodes.ERROR.code),
                        sender
                    )
                }
            }
        }
    }
}
