package dev.cubicchunks;

import dev.cubicchunks.worldgen.GeneratedSectionsData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Registers the mod's chunk data attachments. */
public final class CubicAttachments {

    private CubicAttachments() {}

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CubicChunks.MOD_ID);

    /** Which sections of a column were produced by the extension generator (see {@link GeneratedSectionsData}). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<GeneratedSectionsData>> GENERATED_SECTIONS =
            ATTACHMENT_TYPES.register("generated_sections",
                    () -> AttachmentType.serializable(GeneratedSectionsData::new).build());
}
