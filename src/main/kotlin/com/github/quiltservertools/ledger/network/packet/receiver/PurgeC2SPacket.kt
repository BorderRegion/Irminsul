package com.github.quiltservertools.ledger.network.packet.receiver

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actionutils.RollbackOperations
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.commands.arguments.SearchParamArgument
import com.github.quiltservertools.ledger.config.SearchSpec
import com.github.quiltservertools.ledger.config.config
import com.github.quiltservertools.ledger.network.packet.LedgerPacketTypes
import com.github.quiltservertools.ledger.network.packet.response.ResponseCodes
import com.github.quiltservertools.ledger.network.packet.response.ResponseContent
import com.github.quiltservertools.ledger.network.packet.response.ResponseS2CPacket
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

data class PurgeC2SPacket(val input: String) : CustomPayload {

    override fun getId() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<PurgeC2SPacket> {
        val ID: CustomPayload.Id<PurgeC2SPacket> = CustomPayload.Id(LedgerPacketTypes.PURGE.id)
        val CODEC: PacketCodec<PacketByteBuf, PurgeC2SPacket> = CustomPayload.codecOf({ _, _ -> TODO() }, {
            PurgeC2SPacket(it.readString())
        })

        @Suppress("TooGenericExceptionCaught")
        override fun receive(payload: PurgeC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            val sender = context.responseSender()
            if (!Permissions.check(player, "ledger.networking", CommandConsts.PERMISSION_LEVEL) ||
                !Permissions.check(player, "ledger.commands.purge", config[SearchSpec.purgePermissionLevel])
            ) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(LedgerPacketTypes.PURGE.id, ResponseCodes.NO_PERMISSION.code),
                    sender
                )
                return
            }

            val params = try {
                SearchParamArgument.get(payload.input, player.commandSource).also { it.ensurePurgeScoped() }
            } catch (throwable: Throwable) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(LedgerPacketTypes.PURGE.id, ResponseCodes.ERROR.code),
                    sender
                )
                return
            }

            ResponseS2CPacket.sendResponse(
                ResponseContent(LedgerPacketTypes.PURGE.id, ResponseCodes.EXECUTING.code),
                sender
            )

            Ledger.launch {
                try {
                    RollbackOperations.purge(params)
                    ResponseS2CPacket.sendResponse(
                        ResponseContent(LedgerPacketTypes.PURGE.id, ResponseCodes.COMPLETED.code),
                        sender
                    )
                } catch (throwable: Throwable) {
                    Ledger.logger.warn("Network Ledger purge failed", throwable)
                    ResponseS2CPacket.sendResponse(
                        ResponseContent(LedgerPacketTypes.PURGE.id, ResponseCodes.ERROR.code),
                        sender
                    )
                }
            }
        }
    }
}
