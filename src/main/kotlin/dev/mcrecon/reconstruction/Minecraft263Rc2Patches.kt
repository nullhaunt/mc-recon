package dev.mcrecon.reconstruction

/** Narrow, exact-context escape hatches for 26.3-rc-2 constructs with no safe general rewrite. */
object Minecraft263Rc2Patches {
    val ALL = listOf(
        patch(
            "VF-SCOPE-001", "com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.java",
            "Open a lexical scope for a switch arm whose local collides with an earlier arm",
            "case 9:\n                  String blockName = \"_push_constants_instance\";",
            "case 9: {\n                  String blockName = \"_push_constants_instance\";",
        ),
        patch(
            "VF-SCOPE-001-END", "com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.java",
            "Close the lexical scope restored for the push-constant switch arm",
            "Spvc.spvc_compiler_set_name(compiler, resource.base_type_id(), \"_push_constants\");\n            }",
            "Spvc.spvc_compiler_set_name(compiler, resource.base_type_id(), \"_push_constants\");\n                   break;\n                }\n            }",
        ),
        patch(
            "VF-RECORD-001", "com/mojang/renderpearl/frontend/FrontendRenderPass.java",
            "Restore the TextureViewAndSampler record pattern represented by invalid temporary variables",
            """                         if (!(value instanceof TextureViewAndSampler var9)) {
                            throw new IllegalArgumentException("Sampler value must be TextureViewAndSampler");
                         }

                         TextureViewAndSampler var10000 = var9;

                         try {
                            var30 = var10000.view();
                         } catch (Throwable var18) {
                            throw new MatchException(var18.toString(), var18);
                         }

                         GpuTextureView length = var30;
                         GpuTextureView textureView = length;
                         var10000 = var9;

                         try {
                            var32 = var10000.sampler();
                         } catch (Throwable var17) {
                            throw new MatchException(var17.toString(), var17);
                         }

                         GpuSampler var26 = var32;
                         GpuSampler sampler = var26;
""",
            """                         if (!(value instanceof TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler))) {
                            throw new IllegalArgumentException("Sampler value must be TextureViewAndSampler");
                         }
""",
        ),
        patch(
            "VF-RECORD-002", "net/minecraft/world/entity/SteppedInterpolationHandler.java",
            "Restore a PositionPath.Stepped record pattern represented by invalid extraction temporaries",
            """            case PositionPath.Stepped var9: {
               PositionPath.Stepped var10000 = var9;

               try {
                  var10000.endPosition();
               } catch (Throwable var17) {
                  throw new MatchException(var17.toString(), var17);
               }

               var10000 = var9;

               try {
                  var24 = var10000.steps();
               } catch (Throwable var16) {
                  throw new MatchException(var16.toString(), var16);
               }

               List totalInterpolationTicks = var24;
               List<PositionStep> steps = totalInterpolationTicks;
""",
            """            case PositionPath.Stepped(Vec3 ignoredEndPosition, List<PositionStep> steps): {
""",
        ),
        patch(
            "VF-RECORD-003", "net/minecraft/network/protocol/game/VecDeltaCodec.java",
            "Restore a PositionPath.Stepped record pattern represented by invalid extraction temporaries",
            """         case PositionPath.Stepped var6 -> {
            PositionPath.Stepped var10000 = var6;

            try {
               var10000.endPosition();
            } catch (Throwable var10) {
               throw new MatchException(var10.toString(), var10);
            }

            var10000 = var6;

            try {
               var14 = var10000.steps();
            } catch (Throwable var9) {
               throw new MatchException(var9.toString(), var9);
            }

            List var8 = var14;
            List<PositionStep> steps = var8;
""",
            """         case PositionPath.Stepped(Vec3 ignoredEndPosition, List<PositionStep> steps) -> {
""",
        ),
        patch(
            "VF-SCOPE-002", "net/minecraft/client/gui/screens/packs/PackSelectionScreen.java",
            "Use the emitted catch parameter instead of Vineflower's undefined alias",
            "LOGGER.warn(\"Failed to copy datapack file  from {} to {}\", path, targetDir, ex);",
            "LOGGER.warn(\"Failed to copy datapack file  from {} to {}\", path, targetDir, e);",
        ),
        patch(
            "VF-SCOPE-003", "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen.java",
            "Use emitted catch parameters instead of Vineflower's undefined aliases",
            """LOGGER.warn("Failed to remove temporary file {}", path, ex);""",
            """LOGGER.warn("Failed to remove temporary file {}", path, e);""",
        ),
        patch(
            "VF-SCOPE-004", "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen.java",
            "Use the emitted catch parameter instead of Vineflower's undefined alias",
            "throw new UncheckedIOException(ex);",
            "throw new UncheckedIOException(e);",
        ),
        patch(
            "VF-SCOPE-005", "net/minecraft/util/filefix/FileFixerUpper.java",
            "Restore the three emitted catch parameter names used by nested recovery paths",
            """LOGGER.warn("Failed to move outdated world folder out of the way; will try to delete instead: ", ex);

                  try {
                     deleteDirectory(worldFolder);
                  } catch (Exception e2) {
                     LOGGER.warn("Failed to delete outdated world folder: ", ex);
                     throw new FailedCleanupFileFixException(ex, tempWorldTopLevel.getFileName().toString(), fileSystemCapabilities);
                  }
""",
            """LOGGER.warn("Failed to move outdated world folder out of the way; will try to delete instead: ", e);

                  try {
                     deleteDirectory(worldFolder);
                  } catch (Exception e2) {
                     LOGGER.warn("Failed to delete outdated world folder: ", e2);
                     throw new FailedCleanupFileFixException(e2, tempWorldTopLevel.getFileName().toString(), fileSystemCapabilities);
                  }
""",
        ),
        patch(
            "VF-SCOPE-006", "net/minecraft/util/filefix/FileFixerUpper.java",
            "Use the emitted catch parameter instead of Vineflower's undefined alias",
            """LOGGER.warn("Failed to move in new world folder: ", exx);
                  throw new FailedCleanupFileFixException(exx, tempWorldTopLevel.getFileName().toString(), fileSystemCapabilities);""",
            """LOGGER.warn("Failed to move in new world folder: ", e);
                  throw new FailedCleanupFileFixException(e, tempWorldTopLevel.getFileName().toString(), fileSystemCapabilities);""",
        ),
        patch(
            "VF-SCOPE-007", "net/minecraft/client/renderer/LevelEventHandler.java",
            "Rename switch-arm locals that Vineflower emitted into an earlier arm's scope",
            """               double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
               double y = pos.getY() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
               double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
               this.level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.0, 0.0);
               this.level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.0, 0.0);""",
            """               double particleX = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
               double particleY = pos.getY() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
               double particleZ = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
               this.level.addParticle(ParticleTypes.SMOKE, particleX, particleY, particleZ, 0.0, 0.0, 0.0);
               this.level.addParticle(ParticleTypes.FLAME, particleX, particleY, particleZ, 0.0, 0.0, 0.0);""",
        ),
        patch(
            "VF-SCOPE-008", "net/minecraft/client/renderer/LevelEventHandler.java",
            "Rename switch-arm locals that Vineflower emitted into an earlier arm's scope",
            """               double x = Mth.lerp(randomDistance, toPos.getX(), pos.getX()) + (random.nextDouble() - 0.5) * bbWidth * 2.0;
               double y = Mth.lerp(randomDistance, toPos.getY(), pos.getY()) + random.nextDouble() * bbHeight;
               double z = Mth.lerp(randomDistance, toPos.getZ(), pos.getZ()) + (random.nextDouble() - 0.5) * bbWidth * 2.0;
               this.level.addParticle(ParticleTypes.PORTAL, x, y, z, velocityX, velocityY, velocityZ);""",
            """               double teleportX = Mth.lerp(randomDistance, toPos.getX(), pos.getX()) + (random.nextDouble() - 0.5) * bbWidth * 2.0;
               double teleportY = Mth.lerp(randomDistance, toPos.getY(), pos.getY()) + random.nextDouble() * bbHeight;
               double teleportZ = Mth.lerp(randomDistance, toPos.getZ(), pos.getZ()) + (random.nextDouble() - 0.5) * bbWidth * 2.0;
               this.level.addParticle(ParticleTypes.PORTAL, teleportX, teleportY, teleportZ, velocityX, velocityY, velocityZ);""",
        ),
        patch(
            "VF-PATTERN-001", "net/minecraft/advancements/predicates/entity/EntityFlagsPredicate.java",
            "Split a conditional whose repeated pattern bindings overlap under Java scope rules",
            """            return this.isFallFlying.isPresent() && entity instanceof LivingEntity living && living.isFallFlying() != this.isFallFlying.get()
               ? false
               : !(this.isBaby.isPresent() && entity instanceof LivingEntity living) || living.isBaby() == this.isBaby.get();""",
            """            if (this.isFallFlying.isPresent()
               && (!(entity instanceof LivingEntity fallFlyingEntity) || fallFlyingEntity.isFallFlying() != this.isFallFlying.get())) {
               return false;
            }

            return !this.isBaby.isPresent()
               || !(entity instanceof LivingEntity babyEntity)
               || babyEntity.isBaby() == this.isBaby.get();""",
        ),
        patch(
            "VF-SCOPE-009", "com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.java",
            "Rename a push-constant switch-arm local that remains in the preceding arm's scope",
            "String blockName = \"_push_constants_instance\";",
            "String pushConstantBlockName = \"_push_constants_instance\";",
        ),
        patch(
            "VF-SCOPE-009-REFS", "com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.java",
            "Update uses of the renamed push-constant switch-arm local",
            """String pushConstantBlockName = "_push_constants_instance";
if (SHADER_DEBUG_MODE) {
   String existingBlockName = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
   if (existingBlockName != null) {
      blockName = existingBlockName;
   }
}

Spvc.spvc_compiler_set_name(compiler, resource.id(), blockName);""",
            """String pushConstantBlockName = "_push_constants_instance";
if (SHADER_DEBUG_MODE) {
   String existingBlockName = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
   if (existingBlockName != null) {
      pushConstantBlockName = existingBlockName;
   }
}

Spvc.spvc_compiler_set_name(compiler, resource.id(), pushConstantBlockName);""",
        ),
        patch(
            "VF-INFERENCE-001", "net/minecraft/advancements/predicates/MinMaxBounds.java",
            "Restore the explicit recursive type argument lost from an empty Optional constructor call",
            "return new MinMaxBounds.Bounds<>(Optional.empty(), Optional.empty());",
            "return new MinMaxBounds.Bounds<T>(Optional.empty(), Optional.empty());",
        ),
        patch(
            "VF-INFERENCE-002", "net/minecraft/client/Minecraft.java",
            "Disambiguate the value-returning task overload used for resource reload",
            "this.<CompletableFuture<Void>>submit(this::reloadResourcePacks)",
            "this.<CompletableFuture<Void>>submit((java.util.function.Supplier<CompletableFuture<Void>>)this::reloadResourcePacks)",
        ),
        patch(
            "VF-INFERENCE-003", "net/minecraft/client/OptionInstance.java",
            "Restore the Supplier target type for the cycle button's default value",
            "CycleButton.builder(instance.toString, instance::get)",
            "CycleButton.builder(instance.toString, (Supplier<T>)instance::get)",
        ),
        patch(
            "VF-GENERIC-PATCH-001", "net/minecraft/client/gui/components/ChatComponent.java",
            "Pass the wildcard chat constructor by capture conversion instead of an invalid Screen parameterization",
            "this.minecraft.gui.setScreen(this.createScreen(chatMethod, (ChatScreen.ChatConstructor<Screen>)chat));",
            "this.minecraft.gui.setScreen(this.createScreen(chatMethod, chat));",
        ),
        patch(
            "VF-INFERENCE-004", "net/minecraft/client/gui/screens/PresetFlatWorldScreen.java",
            "Widen the Optional mapping result from MutableComponent to Component",
            "return Component.translatable(key.identifier().toLanguageKey(\"flat_world_preset\"));",
            "return (Component)Component.translatable(key.identifier().toLanguageKey(\"flat_world_preset\"));",
        ),
        patch(
            "VF-INFERENCE-005", "net/minecraft/client/gui/screens/inventory/BookViewScreen.java",
            "Select Component as the stream mapping result instead of MutableComponent",
            ".map(Component::literal).toList()) : null;",
            ".<Component>map(Component::literal).toList()) : null;",
        ),
        patch(
            "VF-INFERENCE-006", "net/minecraft/client/gui/screens/worldselection/WorldCreationUiState.java",
            "Widen the Optional mapping result from MutableComponent to Component",
            "return Component.translatable(key.identifier().toLanguageKey(\"generator\"));",
            "return (Component)Component.translatable(key.identifier().toLanguageKey(\"generator\"));",
        ),
        patch(
            "VF-GENERIC-PATCH-002", "net/minecraft/client/renderer/block/dispatch/VariantSelector.java",
            "Preserve the Property wildcard capture instead of inventing an out-of-bounds Comparable parameterization",
            "Comparable<?> value = getValueHelper((Property<Comparable<?>>)property, propertyValue);",
            "Comparable<?> value = getValueHelper(property, propertyValue);",
        ),
        patch(
            "VF-GENERIC-PATCH-003", "net/minecraft/client/renderer/entity/EntityRenderDispatcher.java",
            "Bridge the erased renderer result after pattern matching instead of parameterizing AvatarRenderer with unconstrained T",
            "case AbstractClientPlayer player -> this.getAvatarRenderer((Map<PlayerModelType, AvatarRenderer<T>>)this.playerRenderers, (T)player);",
            "case AbstractClientPlayer player -> (EntityRenderer<? super T, ?>)(EntityRenderer<?, ?>)this.getAvatarRenderer(this.playerRenderers, player);",
        ),
        patch(
            "VF-GENERIC-PATCH-004", "net/minecraft/client/renderer/entity/EntityRenderDispatcher.java",
            "Bridge the erased mannequin renderer result after pattern matching",
            "case ClientMannequin mannequin -> this.getAvatarRenderer((Map<PlayerModelType, AvatarRenderer<T>>)this.mannequinRenderers, (T)mannequin);",
            "case ClientMannequin mannequin -> (EntityRenderer<? super T, ?>)(EntityRenderer<?, ?>)this.getAvatarRenderer(this.mannequinRenderers, mannequin);",
        ),
        patch(
            "VF-GENERIC-PATCH-005", "net/minecraft/client/renderer/entity/EntityRenderDispatcher.java",
            "Bridge the erased avatar render-state renderer to the generic state return type",
            "? this.getRenderer(avatarRenderState)",
            "? (EntityRenderer<?, ? super S>)(EntityRenderer<?, ?>)this.getRenderer(avatarRenderState)",
        ),
        patch(
            "VF-GENERIC-PATCH-006", "net/minecraft/client/renderer/feature/ModelFeatureRenderer.java",
            "Use a wildcard erasure bridge for the invariant feature renderer type",
            "return (FeatureRendererType<ModelFeatureRenderer.Submit<S>>)ModelFeatureRenderer.TYPE;",
            "return (FeatureRendererType<ModelFeatureRenderer.Submit<S>>)(FeatureRendererType<?>)ModelFeatureRenderer.TYPE;",
        ),
        patch(
            "VF-GENERIC-PATCH-007", "net/minecraft/client/renderer/item/ConditionalItemModel.java",
            "Invoke the context swapper through its erased codec boundary before restoring T",
            "return (T)contextSwapper.swapTo(originalProperty.type().codec(), originalProperty, context.registryAccess()).result().orElse(originalProperty);",
            "return (T)contextSwapper.swapTo((MapCodec)originalProperty.type().codec(), originalProperty, context.registryAccess()).result().orElse(originalProperty);",
        ),
        patch(
            "VF-GENERIC-PATCH-008", "net/minecraft/client/resources/model/ModelBakery.java",
            "Use the operation cache's erased map contract for its heterogeneously typed keys",
            "return (T)this.operationCache.computeIfAbsent(key, this.cacheComputeFunction);",
            "return (T)((Map)this.operationCache).computeIfAbsent(key, (Function)this.cacheComputeFunction);",
        ),
        patch(
            "VF-GENERIC-PATCH-009", "net/minecraft/client/resources/palette/PalettedTextureManager.java",
            "Retain Optional<BaseTexture> typing in the cache removal listener",
            "((Optional)notification.getValue()).ifPresent(PalettedTextureManager.BaseTexture::close);",
            "((Optional<PalettedTextureManager.BaseTexture>)notification.getValue()).ifPresent(PalettedTextureManager.BaseTexture::close);",
        ),
        patch(
            "VF-GENERIC-PATCH-010", "net/minecraft/commands/execution/tasks/BuildContexts.java",
            "Match the reifiable wildcard form of the custom modifier and restore T after the test",
            "if (modifier instanceof CustomModifierExecutor<T> customModifierExecutor) {",
            "if (modifier instanceof CustomModifierExecutor<?> rawCustomModifierExecutor) {\n                   CustomModifierExecutor<T> customModifierExecutor = (CustomModifierExecutor<T>)rawCustomModifierExecutor;",
        ),
        patch(
            "VF-INFERENCE-007", "net/minecraft/commands/execution/tasks/BuildContexts.java",
            "Restore command-source type witnesses for the fallthrough queue entry",
            "new CommandQueueEntry<>(frame, FallthroughTask.instance())",
            "new CommandQueueEntry<T>(frame, FallthroughTask.<T>instance())",
        ),
        patch(
            "VF-GENERIC-PATCH-011", "net/minecraft/commands/execution/tasks/BuildContexts.java",
            "Match the reifiable wildcard form of the custom command and restore T after the test",
            "if (executeContext.getCommand() instanceof CustomCommandExecutor<T> customCommandExecutor) {",
            "if (executeContext.getCommand() instanceof CustomCommandExecutor<?> rawCustomCommandExecutor) {\n             CustomCommandExecutor<T> customCommandExecutor = (CustomCommandExecutor<T>)rawCustomCommandExecutor;",
        ),
        patch(
            "VF-GENERIC-PATCH-012", "net/minecraft/commands/synchronization/ArgumentTypeInfos.java",
            "Restore erased class tokens for registry entries whose generic argument classes are not reifiable",
            "register(registry, \"resource_or_tag\", fixClassType(ResourceOrTagArgument.class), new ResourceOrTagArgument.Info());",
            "register(registry, \"resource_or_tag\", (Class)ResourceOrTagArgument.class, new ResourceOrTagArgument.Info());",
        ),
        patch(
            "VF-GENERIC-PATCH-013", "net/minecraft/commands/synchronization/ArgumentTypeInfos.java",
            "Restore erased class tokens for registry entries whose generic argument classes are not reifiable",
            "register(registry, \"resource_or_tag_key\", fixClassType(ResourceOrTagKeyArgument.class), new ResourceOrTagKeyArgument.Info());",
            "register(registry, \"resource_or_tag_key\", (Class)ResourceOrTagKeyArgument.class, new ResourceOrTagKeyArgument.Info());",
        ),
        patch(
            "VF-GENERIC-PATCH-014", "net/minecraft/commands/synchronization/ArgumentTypeInfos.java",
            "Restore erased class tokens for registry entries whose generic argument classes are not reifiable",
            "register(registry, \"resource\", fixClassType(ResourceArgument.class), new ResourceArgument.Info());",
            "register(registry, \"resource\", (Class)ResourceArgument.class, new ResourceArgument.Info());",
        ),
        patch(
            "VF-GENERIC-PATCH-015", "net/minecraft/commands/synchronization/ArgumentTypeInfos.java",
            "Restore erased class tokens for registry entries whose generic argument classes are not reifiable",
            "register(registry, \"resource_key\", fixClassType(ResourceKeyArgument.class), new ResourceKeyArgument.Info());",
            "register(registry, \"resource_key\", (Class)ResourceKeyArgument.class, new ResourceKeyArgument.Info());",
        ),
        patch(
            "VF-GENERIC-PATCH-016", "net/minecraft/commands/synchronization/ArgumentTypeInfos.java",
            "Restore erased class tokens for registry entries whose generic argument classes are not reifiable",
            "register(registry, \"resource_selector\", fixClassType(ResourceSelectorArgument.class), new ResourceSelectorArgument.Info());",
            "register(registry, \"resource_selector\", (Class)ResourceSelectorArgument.class, new ResourceSelectorArgument.Info());",
        ),
        patch(
            "VF-GENERIC-PATCH-017", "net/minecraft/commands/synchronization/SuggestionProviders.java",
            "Bridge the registered shared-provider wrapper through its erased interface",
            "return new SuggestionProviders.RegisteredSuggestion(name, provider);",
            "return (SuggestionProvider<S>)(SuggestionProvider<?>)new SuggestionProviders.RegisteredSuggestion(name, provider);",
        ),
        patch(
            "VF-INFERENCE-008", "net/minecraft/core/Registry.java",
            "Use an empty list of holders, matching the Optional branch's element type",
            "DataFixUtils.orElse(this.get(id), List.<T>of())",
            "DataFixUtils.orElse(this.get(id), List.<Holder<T>>of())",
        ),
        patch(
            "VF-GENERIC-PATCH-018", "net/minecraft/core/component/DataComponentType.java",
            "Bridge the heterogeneous data-component registry through Registry<?> before restoring its local type",
            "(Registry<DataComponentType.Builder.SimpleType<T>>)BuiltInRegistries.DATA_COMPONENT_TYPE",
            "(Registry<DataComponentType.Builder.SimpleType<T>>)(Registry<?>)BuiltInRegistries.DATA_COMPONENT_TYPE",
        ),
        patch(
            "VF-GENERIC-PATCH-019", "net/minecraft/core/component/TypedDataComponent.java",
            "Let wildcard capture flow into the typed decoder helper",
            "return decodeTyped(input, (DataComponentType<T>)type);",
            "return decodeTyped(input, type);",
        ),
        patch(
            "VF-GENERIC-PATCH-020", "net/minecraft/core/component/TypedDataComponent.java",
            "Let wildcard capture flow into the typed encoder helper",
            "encodeCap(output, (TypedDataComponent<T>)value);",
            "encodeCap(output, value);",
        ),
        patch(
            "VF-GENERIC-PATCH-021", "net/minecraft/core/registries/BuiltInRegistries.java",
            "Bridge the heterogeneous writable registry key through ResourceKey<?> before registration",
            "WRITABLE_REGISTRY.register((ResourceKey<WritableRegistry<?>>)name, registry, RegistrationInfo.BUILT_IN);",
            "WRITABLE_REGISTRY.register((ResourceKey<WritableRegistry<?>>)(ResourceKey<?>)name, registry, RegistrationInfo.BUILT_IN);",
        ),
        patch(
            "VF-GENERIC-PATCH-022", "net/minecraft/core/registries/codec/HolderSetCodec.java",
            "Widen the named holder set result to its HolderSet interface",
            "return registry.get(key).map(DataResult::success).orElseGet(() -> {",
            "return registry.get(key).<DataResult<HolderSet<E>>>map(named -> DataResult.success(named)).orElseGet(() -> {",
        ),
        patch(
            "VF-GENERIC-PATCH-023", "net/minecraft/core/registries/codec/HolderSetCodec.java",
            "Use the holder owner's element parameter rather than the DynamicOps parameter",
            "named.canSerializeIn((HolderOwner<T>)maybeOwner.get())",
            "named.canSerializeIn((HolderOwner<E>)maybeOwner.get())",
        ),
        patch(
            "VF-GENERIC-PATCH-024", "net/minecraft/data/info/RegistryDumpReport.java",
            "Query the registry-of-registries through its erased element boundary",
            "int registryId = BuiltInRegistries.REGISTRY.getId(registry);",
            "int registryId = ((Registry)BuiltInRegistries.REGISTRY).getId(registry);",
        ),
        patch(
            "VF-SCOPE-010", "net/minecraft/locale/Language.java",
            "Use the emitted formatted-character sink lambda parameter",
            "StringDecomposer.iterateFormatted(contents, style, output)",
            "StringDecomposer.iterateFormatted(contents, style, outputx)",
        ),
        patch(
            "VF-GENERIC-PATCH-025", "net/minecraft/network/codec/StreamCodec.java",
            "Express the interface's intentional contravariant buffer narrowing via erasure",
            "return this;\n   }\n\n   @FunctionalInterface\n   interface CodecOperation",
            "return (StreamCodec<S, V>)(StreamCodec<?, V>)this;\n   }\n\n   @FunctionalInterface\n   interface CodecOperation",
        ),
        patch(
            "VF-GENERIC-PATCH-026", "net/minecraft/network/protocol/common/custom/CustomPacketPayload.java",
            "Bridge the selected wildcard packet codec to the captured payload type",
            "StreamCodec<B, T> codec = this.findCodec(type.id);",
            "StreamCodec<B, T> codec = (StreamCodec<B, T>)(StreamCodec<?, ?>)this.findCodec(type.id);",
        ),
        patch(
            "VF-RECORD-004", "net/minecraft/network/protocol/game/VecDelta.java",
            "Restore the typed record component pattern for stepped deltas",
            "case VecDelta.Stepped(List var20):",
            "case VecDelta.Stepped(var steps):",
        ),
        patch(
            "VF-RECORD-004-BODY", "net/minecraft/network/protocol/game/VecDelta.java",
            "Use the typed stepped record component directly",
            "List var10 = var20;\n\nfor (VecDelta.Stepped.DeltaStep step : var10) {",
            "for (VecDelta.Stepped.DeltaStep step : steps) {",
        ),
        patch(
            "VF-INFERENCE-009", "net/minecraft/network/syncher/EntityDataSerializers.java",
            "Select LivingEntity for the entity-reference stream codec",
            "EntityReference.streamCodec().apply(ByteBufCodecs::optional)",
            "EntityReference.<LivingEntity>streamCodec().apply(ByteBufCodecs::optional)",
        ),
        patch(
            "VF-GENERIC-PATCH-027", "net/minecraft/client/renderer/item/ConditionalItemModel.java",
            "Convert the property's MapCodec to the Codec required by the registry context swapper",
            "(MapCodec)originalProperty.type().codec(), originalProperty",
            "originalProperty.type().codec().codec(), originalProperty",
        ),
        patch(
            "VF-INFERENCE-010", "net/minecraft/resources/RegistryDataLoader.java",
            "Give the resource-key comparator's method references an explicit input type",
            "Comparator.comparing(ResourceKey::registry).thenComparing(ResourceKey::identifier)",
            "Comparator.comparing((ResourceKey<?> key) -> key.registry()).thenComparing(key -> key.identifier())",
        ),
        patch(
            "VF-GENERIC-PATCH-028", "net/minecraft/server/commands/PlaceCommand.java",
            "Remove a raw RequiredArgumentBuilder cast that erased the enclosing command tree's source type",
            "((RequiredArgumentBuilder)Commands.argument(\"feature\", ResourceOrIdArgument.feature(context))",
            "(Commands.argument(\"feature\", ResourceOrIdArgument.feature(context))",
        ),
        patch(
            "VF-GENERIC-PATCH-029", "net/minecraft/server/dedicated/Settings.java",
            "Construct the generic non-static inner value through the current Settings instance",
            "return new Settings.MutableValue<>(key, result, serializer);",
            "return this.new MutableValue<>(key, result, serializer);",
        ),
        patch(
            "VF-GENERIC-PATCH-030", "net/minecraft/server/dialog/action/StaticAction.java",
            "Bridge each action's covariant click-event codec through MapCodec<?> before wrapping it",
            "MapCodec<ClickEvent> mapCodec = action.valueCodec();",
            "MapCodec<ClickEvent> mapCodec = (MapCodec<ClickEvent>)(MapCodec<?>)action.valueCodec();",
        ),
        patch(
            "VF-INFERENCE-011", "net/minecraft/server/level/ChunkTaskPriorityQueue.java",
            "Restore the nested queue value type on the stream-created map",
            "return new Long2ObjectLinkedOpenHashMap<>();",
            "return new Long2ObjectLinkedOpenHashMap<List<Runnable>>();",
        ),
        patch(
            "VF-INFERENCE-012", "net/minecraft/server/network/ServerGamePacketListenerImpl.java",
            "Widen the filterable page mapping result from MutableComponent to Component",
            "return this.filterableFromOutgoing(page).map(Component::literal);",
            "return this.filterableFromOutgoing(page).<Component>map(Component::literal);",
        ),
        patch(
            "VF-GENERIC-PATCH-031", "net/minecraft/server/packs/metadata/MetadataSectionType.java",
            "Bridge a value guarded by metadata-section type identity to the requested Optional type",
            "return type == this.type ? Optional.of(this.value) : Optional.empty();",
            "return type == this.type ? (Optional<U>)(Optional<?>)Optional.of(this.value) : Optional.empty();",
        ),
        patch(
            "VF-GENERIC-PATCH-032", "net/minecraft/util/AbstractListBuilder.java",
            "Return the builder type from append instead of the list element type",
            "return (T)this.append((B)b, value);",
            "return this.append((B)b, value);",
        ),
        patch(
            "VF-GENERIC-PATCH-033", "net/minecraft/util/CubicSpline.java",
            "Widen the Either branches to their common CubicSpline interface",
            "Function<? super CubicSpline.Multipoint<I>, ? extends CubicSpline.Constant<I>>",
            "Function<CubicSpline.Multipoint<I>, CubicSpline<I>>",
        ),
        patch(
            "VF-GENERIC-PATCH-034", "net/minecraft/util/CubicSpline.java",
            "Bridge a coordinate-independent constant spline to the requested coordinate type",
            "public <R extends BoundedFloatFunction<?>> CubicSpline<R> mapCoordinates(final Function<I, R> mapper) {\n   return this;",
            "public <R extends BoundedFloatFunction<?>> CubicSpline<R> mapCoordinates(final Function<I, R> mapper) {\n   return (CubicSpline<R>)(CubicSpline<?>)this;",
        ),
        patch(
            "VF-GENERIC-PATCH-035", "net/minecraft/util/EncoderCache.java",
            "Restore the DynamicOps result type after reading the heterogeneous encoder cache",
            "return EncoderCache.this.cache.getUnchecked(new EncoderCache.Key<>(codec, input, ops)).map(value -> {",
            "return ((DataResult<T>)(DataResult<?>)EncoderCache.this.cache.getUnchecked(new EncoderCache.Key<>(codec, input, ops))).map(value -> {",
        ),
        patch(
            "VF-GENERIC-PATCH-036", "net/minecraft/util/ExtraCodecs.java",
            "Type the temporary Pair codec with the interval point type",
            "RecordCodecBuilder.<Pair>create(",
            "RecordCodecBuilder.<Pair<P, P>>create(",
        ),
        patch(
            "VF-INFERENCE-013", "net/minecraft/util/HashOps.java",
            "Give the Pair comparator's key extractor an explicit input type",
            "Comparator.comparing(Pair::getFirst, HASH_COMPARATOR)",
            "Comparator.comparing((Pair<HashCode, HashCode> pair) -> pair.getFirst(), HASH_COMPARATOR)",
        ),
        patch(
            "VF-INFERENCE-014", "net/minecraft/util/HashOps.java",
            "Give the Pair comparator's value extractor an explicit input type",
            ".thenComparing(Pair::getSecond, HASH_COMPARATOR);",
            ".thenComparing(pair -> pair.getSecond(), HASH_COMPARATOR);",
        ),
        patch(
            "VF-GENERIC-PATCH-037", "net/minecraft/util/PlaceholderLookupProvider.java",
            "Bridge the universal lookup through HolderGetter<?> to the requested element type",
            "public <T> HolderGetter<T> castAsLookup() {\n   return this;",
            "public <T> HolderGetter<T> castAsLookup() {\n   return (HolderGetter<T>)(HolderGetter<?>)this;",
        ),
        patch(
            "VF-INFERENCE-015", "net/minecraft/util/SortedArraySet.java",
            "Restore the explicit element type on the natural-order set constructor",
            "return new SortedArraySet<>(initialCapacity, Comparator.naturalOrder());",
            "return new SortedArraySet<T>(initialCapacity, Comparator.naturalOrder());",
        ),
        patch(
            "VF-GENERIC-PATCH-038", "net/minecraft/util/Util.java",
            "Iterate the enum constants at their declared K type rather than through raw Enum[]",
            "for (K key : (Enum[])keyType.getEnumConstants()) {",
            "for (K key : keyType.getEnumConstants()) {",
        ),
        patch(
            "VF-INFERENCE-016", "net/minecraft/util/datafix/DataFixers.java",
            "Restore String as the value type of the cat variant lookup map",
            "\"CatType\", Util.make(new Int2ObjectOpenHashMap(), m -> {",
            "\"CatType\", Util.make(new Int2ObjectOpenHashMap<String>(), m -> {",
        ),
        patch(
            "VF-INFERENCE-017", "net/minecraft/util/datafix/DataFixers.java",
            "Restore String as the value type of the frog variant lookup map",
            "\"Variant\", Util.make(new Int2ObjectOpenHashMap(), m -> {",
            "\"Variant\", Util.make(new Int2ObjectOpenHashMap<String>(), m -> {",
        ),
        patch(
            "VF-GENERIC-PATCH-039", "net/minecraft/util/datafix/fixes/BlockEntityUUIDFix.java",
            "Widen the captured Dynamic result before falling back to the input tag",
            "}).result().orElse(tag);",
            "}).result().map(value -> (Dynamic<?>)value).orElse(tag);",
        ),
        patch(
            "VF-INFERENCE-018", "net/minecraft/util/datafix/fixes/ChunkProtoTickListFix.java",
            "Type the dynamic map builder used to create a tick entry",
            "ImmutableMap.builder()",
            "ImmutableMap.<Dynamic<?>, Dynamic<?>>builder()",
        ),
        patch(
            "VF-GENERIC-PATCH-040", "net/minecraft/util/datafix/fixes/OptionsKeyLwjgl3Fix.java",
            "Widen the captured Dynamic update result before using the original tag fallback",
            "}).result().orElse(tag);",
            "}).result().map(value -> (Dynamic<?>)value).orElse(tag);",
        ),
        patch(
            "VF-GENERIC-PATCH-041", "net/minecraft/util/datafix/fixes/OptionsKeyTranslationFix.java",
            "Widen the captured Dynamic update result before using the original tag fallback",
            "}).result().orElse(tag);",
            "}).result().map(value -> (Dynamic<?>)value).orElse(tag);",
        ),
        patch(
            "VF-GENERIC-PATCH-042", "net/minecraft/world/attribute/EnvironmentAttributeMap.java",
            "Bridge the dispatched entry map through its wildcard value representation",
            ".xmap(EnvironmentAttributeMap::new, v -> {",
            ".xmap(map -> new EnvironmentAttributeMap((Map<EnvironmentAttribute<?>, EnvironmentAttributeMap.Entry<?, ?>>)(Map<?, ?>)map), v -> {",
        ),
        patch(
            "VF-GENERIC-PATCH-043", "net/minecraft/world/attribute/modifier/AttributeModifier.java",
            "Bridge the singleton override modifier through its wildcard parameterization",
            "return AttributeModifier.OverrideModifier.INSTANCE;",
            "return (AttributeModifier<Value, Value>)(AttributeModifier<?, ?>)AttributeModifier.OverrideModifier.INSTANCE;",
        ),
        patch(
            "VF-INFERENCE-019", "net/minecraft/world/entity/EntityReference.java",
            "Select the caller's stored entity type for codec decoding",
            "input.read(key, codec()).orElse(null)",
            "input.read(key, EntityReference.<StoredEntityType>codec()).orElse(null)",
        ),
        patch(
            "VF-INFERENCE-020", "net/minecraft/world/entity/EntityReference.java",
            "Select the caller's stored entity type when converting a legacy owner UUID",
            "return uuid.isPresent() ? of(uuid.get()) : input.getString(key)",
            "return uuid.isPresent() ? EntityReference.<StoredEntityType>of(uuid.get()) : input.getString(key)",
        ),
        patch(
            "VF-INFERENCE-021", "net/minecraft/world/entity/EntityReference.java",
            "Select the caller's stored entity type for the legacy-owner constructor mapping",
            ".map(EntityReference::new).orElse(null);",
            ".map(uuidValue -> new EntityReference<StoredEntityType>(uuidValue)).orElse(null);",
        ),
        patch(
            "VF-GENERIC-PATCH-044", "net/minecraft/world/entity/SpawnPlacements.java",
            "Restore the spawn predicate's entity type after its heterogeneous registry lookup",
            "data.predicate.test(type, level, spawnReason, pos, random)",
            "((SpawnPlacements.SpawnPredicate<T>)(SpawnPlacements.SpawnPredicate<?>)data.predicate).test(type, level, spawnReason, pos, random)",
        ),
        patch(
            "VF-GENERIC-PATCH-045", "net/minecraft/world/entity/ai/Brain.java",
            "Capture the memory slot's element type at the visitor helper boundary",
            "final MemoryModuleType<U> memoryModuleType, final MemorySlot<?> slot",
            "final MemoryModuleType<U> memoryModuleType, final MemorySlot<U> slot",
        ),
        patch(
            "VF-GENERIC-PATCH-046", "net/minecraft/world/entity/ai/Brain.java",
            "Bridge the heterogeneous memory map slot into the visitor helper",
            "callVisitor(visitor, (MemoryModuleType<?>)memoryModuleType, (MemorySlot<?>)slot);",
            "callVisitor(visitor, (MemoryModuleType)memoryModuleType, (MemorySlot)slot);",
        ),
        patch(
            "VF-GENERIC-PATCH-047", "net/minecraft/world/entity/ai/behavior/InteractWith.java",
            "Restore the behavior body subtype after nearest-entity selection",
            "closest.ifPresent(mob -> {",
            "closest.map(mob -> (T)mob).ifPresent(mob -> {",
        ),
        patch(
            "VF-INFERENCE-022", "net/minecraft/world/entity/animal/allay/AllayAi.java",
            "Select Allay as the core activity's entity type",
            "private static ActivityData<Allay> initCoreActivity() {\n   return ActivityData.create(",
            "private static ActivityData<Allay> initCoreActivity() {\n   return ActivityData.<Allay>create(",
        ),
        patch(
            "VF-INFERENCE-023", "net/minecraft/world/entity/animal/allay/AllayAi.java",
            "Select Allay as the idle activity's entity type",
            "private static ActivityData<Allay> initIdleActivity() {\n   return ActivityData.create(",
            "private static ActivityData<Allay> initIdleActivity() {\n   return ActivityData.<Allay>create(",
        ),
        patch(
            "VF-GENERIC-PATCH-048", "net/minecraft/world/entity/animal/allay/AllayAi.java",
            "Restore the Allay entity parameter on the idle RunOne behavior",
            "new RunOne(",
            "new RunOne<Allay>(",
        ),
        patch(
            "VF-INFERENCE-024", "net/minecraft/world/entity/animal/goat/GoatAi.java",
            "Select Goat as the core activity's entity type",
            "private static ActivityData<Goat> initCoreActivity() {\n   return ActivityData.create(",
            "private static ActivityData<Goat> initCoreActivity() {\n   return ActivityData.<Goat>create(",
        ),
        patch(
            "VF-INFERENCE-025", "net/minecraft/world/entity/animal/happyghast/HappyGhastAi.java",
            "Select HappyGhast as the core activity's entity type",
            "private static ActivityData<HappyGhast> initCoreActivity() {\n   return ActivityData.create(",
            "private static ActivityData<HappyGhast> initCoreActivity() {\n   return ActivityData.<HappyGhast>create(",
        ),
        patch(
            "VF-INFERENCE-026", "net/minecraft/world/entity/monster/hoglin/HoglinAi.java",
            "Select Hoglin for the fleeing-memory predicate",
            "EraseMemoryIf.<PathfinderMob>create(HoglinAi::wantsToStopFleeing",
            "EraseMemoryIf.<Hoglin>create(HoglinAi::wantsToStopFleeing",
        ),
        patch(
            "VF-INFERENCE-027", "net/minecraft/world/entity/monster/piglin/PiglinAi.java",
            "Select Piglin as the fight activity's entity type",
            "private static ActivityData<Piglin> initFightActivity(final Piglin body) {\n   return ActivityData.create(",
            "private static ActivityData<Piglin> initFightActivity(final Piglin body) {\n   return ActivityData.<Piglin>create(",
        ),
        patch(
            "VF-INFERENCE-028", "net/minecraft/world/entity/monster/piglin/PiglinAi.java",
            "Select Piglin for the celebration RunOne behavior",
            "new RunOne<LivingEntity>(",
            "new RunOne<Piglin>(",
        ),
        patch(
            "VF-INFERENCE-029", "net/minecraft/world/entity/monster/piglin/PiglinAi.java",
            "Select Piglin for the fleeing-memory predicate",
            "EraseMemoryIf.<PathfinderMob>create(PiglinAi::wantsToStopFleeing",
            "EraseMemoryIf.<Piglin>create(PiglinAi::wantsToStopFleeing",
        ),
        patch(
            "VF-INFERENCE-030", "net/minecraft/world/entity/monster/piglin/PiglinAi.java",
            "Select Piglin for the ride dismount predicate",
            "DismountOrSkipMounting.<LivingEntity>create(8, PiglinAi::wantsToStopRiding)",
            "DismountOrSkipMounting.<Piglin>create(8, PiglinAi::wantsToStopRiding)",
        ),
        patch(
            "VF-GENERIC-PATCH-049", "net/minecraft/world/entity/monster/piglin/StartHuntingHoglin.java",
            "Retain the nearest-piglin list's captured element type",
            "i.<List>tryGet(nearestPiglins).filter",
            "i.tryGet(nearestPiglins).filter",
        ),
        patch(
            "VF-GENERIC-PATCH-049-B", "net/minecraft/world/entity/monster/piglin/StartHuntingHoglin.java",
            "Retain the nearest-piglin list's captured element type in the broadcast path",
            "i.<List>tryGet(nearestPiglins).ifPresent",
            "i.tryGet(nearestPiglins).ifPresent",
        ),
        patch(
            "VF-INFERENCE-031", "net/minecraft/world/entity/variant/PriorityProvider.java",
            "Give the priority comparator an explicit wildcard entry input type",
            "PriorityProvider.UnpackedEntry::priority",
            "(PriorityProvider.UnpackedEntry<?, ?> entry) -> entry.priority()",
        ),
        patch(
            "VF-INFERENCE-032", "net/minecraft/world/item/AdventureModePredicate.java",
            "Widen styled block names from MutableComponent to Component",
            "}).distinct().map(block -> {",
            "}).distinct().<Component>map(block -> {",
        ),
        patch(
            "VF-INFERENCE-033", "net/minecraft/world/item/HoneycombItem.java",
            "Widen the successful waxing Optional branch to InteractionResult",
            "getWaxed(oldState).map(waxedState -> {",
            "getWaxed(oldState).<InteractionResult>map(waxedState -> {",
        ),
        patch(
            "VF-GENERIC-PATCH-050", "net/minecraft/world/item/component/KineticWeapon.java",
            "Restore the entity-hit collection element type through erasure",
            "return (Collection)e;",
            "return (Collection<EntityHitResult>)(Collection<?>)e;",
        ),
        patch(
            "VF-GENERIC-PATCH-051", "net/minecraft/world/item/component/PiercingWeapon.java",
            "Restore the entity-hit collection element type through erasure",
            "return (Collection)e;",
            "return (Collection<EntityHitResult>)(Collection<?>)e;",
        ),
        patch(
            "VF-GENERIC-PATCH-052", "net/minecraft/world/item/crafting/RecipeMap.java",
            "Bridge the heterogeneous recipe collection through Collection<?> before restoring T",
            "return (Collection<RecipeHolder<T>>)this.byType.get(type);",
            "return (Collection<RecipeHolder<T>>)(Collection<?>)this.byType.get(type);",
        ),
        patch(
            "VF-INFERENCE-034", "net/minecraft/world/level/biome/Climate.java",
            "Restore the R-tree leaf's element type at construction",
            "return new Climate.RTree.Leaf(p.getFirst(), p.getSecond());",
            "return new Climate.RTree.Leaf<T>(p.getFirst(), p.getSecond());",
        ),
        patch(
            "VF-GENERIC-PATCH-053", "net/minecraft/world/level/block/BaseEntityBlock.java",
            "Bridge a ticker guarded by block-entity type identity to the requested entity type",
            "return expected == actual ? ticker : null;",
            "return expected == actual ? (BlockEntityTicker<A>)(BlockEntityTicker<?>)ticker : null;",
        ),
        patch(
            "VF-GENERIC-PATCH-054", "net/minecraft/world/level/block/Block.java",
            "Restore the property's captured comparable type after its object-map lookup",
            "return state.setValue(property, (Comparable)value);",
            "return state.setValue(property, (T)value);",
        ),
        patch(
            "VF-INFERENCE-035", "net/minecraft/world/level/block/entity/TestInstanceBlockEntity.java",
            "Widen the test-name Optional branch from MutableComponent to Component",
            "return Component.literal(key.identifier().toString());",
            "return (Component)Component.literal(key.identifier().toString());",
        ),
        patch(
            "VF-INFERENCE-036", "net/minecraft/world/level/dimension/end/EnderDragonFight.java",
            "Select EndCrystal as the entity-reference codec element type",
            "Codec.list(EntityReference.codec()).optionalFieldOf(\"respawn_crystals\"",
            "Codec.list(EntityReference.<EndCrystal>codec()).optionalFieldOf(\"respawn_crystals\"",
        ),
        patch(
            "VF-GENERIC-PATCH-055", "net/minecraft/world/level/gamerules/GameRuleMap.java",
            "Restore the dispatched game-rule map shape at the xmap boundary",
            ".xmap(GameRuleMap::ofTrusted, GameRuleMap::map);",
            ".xmap(map -> GameRuleMap.ofTrusted((Map<GameRule<?>, Object>)(Map<?, ?>)map), GameRuleMap::map);",
        ),
        patch(
            "VF-GENERIC-PATCH-056", "net/minecraft/world/level/levelgen/feature/MultifaceGrowthFeature.java",
            "Retain Direction as the shuffled search list's element type",
            "List var13 = this.getShuffledDirections(random);",
            "List<Direction> var13 = this.getShuffledDirections(random);",
        ),
        patch(
            "VF-GENERIC-PATCH-057", "net/minecraft/world/level/storage/loot/predicates/EnvironmentAttributeCheck.java",
            "Bridge the heterogeneous condition codec through MapCodec<?> before restoring Value",
            "return (MapCodec<EnvironmentAttributeCheck<Value>>)MAP_CODEC;",
            "return (MapCodec<EnvironmentAttributeCheck<Value>>)(MapCodec<?>)MAP_CODEC;",
        ),
        patch(
            "VF-INFERENCE-037", "net/minecraft/advancements/predicates/entity/PlayerPredicate.java",
            "Give the stat codec builder its concrete matcher type instead of a covariant wildcard Mu",
            "(App<Mu<? extends PlayerPredicate.StatMatcher<?>>, Holder<T>>)",
            "(App<Mu<PlayerPredicate.StatMatcher<T>>, Holder<T>>)(App<?, ?>)",
        ),
        patch(
            "VF-INFERENCE-038", "net/minecraft/advancements/predicates/entity/PlayerPredicate.java",
            "Give the range codec builder its concrete matcher type instead of a covariant wildcard Mu",
            "(App<Mu<? extends PlayerPredicate.StatMatcher<?>>, MinMaxBounds.Ints>)",
            "(App<Mu<PlayerPredicate.StatMatcher<T>>, MinMaxBounds.Ints>)(App<?, ?>)",
        ),
        patch(
            "VF-GENERIC-PATCH-058", "net/minecraft/client/renderer/item/ConditionalItemModel.java",
            "Use the single Codec conversion supplied by MapCodec",
            "originalProperty.type().codec().codec()",
            "originalProperty.type().codec()",
        ),
        patch(
            "VF-INFERENCE-039", "net/minecraft/core/component/DataComponentPatch.java",
            "Fix the dispatched patch map's value type at its codec boundary",
            "Codec.dispatchedMap(DataComponentPatch.PatchKey.CODEC, DataComponentPatch.PatchKey::valueCodec)",
            "Codec.<DataComponentPatch.PatchKey, Object>dispatchedMap(DataComponentPatch.PatchKey.CODEC, DataComponentPatch.PatchKey::valueCodec)",
        ),
        patch(
            "VF-INFERENCE-040", "net/minecraft/core/component/predicates/DataComponentPredicate.java",
            "Collect heterogeneous component predicates with an explicit result-map type",
            "return singles.stream().collect(Collectors.toMap(DataComponentPredicate.Single::type, DataComponentPredicate.Single::predicate));",
            "java.util.Map<DataComponentPredicate.Type<?>, DataComponentPredicate> result = new java.util.HashMap<>();\n            for (DataComponentPredicate.Single<?> single : singles) {\n               result.put(single.type(), single.predicate());\n            }\n            return result;",
        ),
        patch(
            "VF-INFERENCE-041", "net/minecraft/network/protocol/game/ClientboundGameRuleValuesPacket.java",
            "Supply all map stream-codec type arguments, including the wildcard game-rule key",
            "ByteBufCodecs.map(",
            "ByteBufCodecs.<FriendlyByteBuf, ResourceKey<GameRule<?>>, String, Map<ResourceKey<GameRule<?>>, String>>map(",
        ),
        patch(
            "VF-INFERENCE-042", "net/minecraft/server/jsonrpc/IncomingRpcMethods.java",
            "Instantiate the generic game-rule update RPC method reference at a concrete erased payload type",
            "IncomingRpcMethod.method(GameRulesService::update)",
            "IncomingRpcMethod.<GameRulesService.GameRuleUpdate<Object>, GameRulesService.GameRuleUpdate<Object>>method(GameRulesService::update)",
        ),
        patch(
            "VF-INFERENCE-043", "net/minecraft/server/jsonrpc/api/MethodInfo.java",
            "Fix the record-codec builder's enclosing generic MethodInfo type",
            "RecordCodecBuilder.mapCodec(",
            "RecordCodecBuilder.<MethodInfo<Params, Result>>mapCodec(",
        ),
        patch(
            "VF-GENERIC-PATCH-059", "net/minecraft/server/jsonrpc/api/MethodInfo.java",
            "Bridge the statically stored wildcard Named codec through Codec<?> after typed construction",
            "public static final Codec<MethodInfo.Named<?, ?>> CODEC = typedCodec();",
            "public static final Codec<MethodInfo.Named<?, ?>> CODEC = (Codec<MethodInfo.Named<?, ?>>)(Codec<?>)typedCodec();",
        ),
        patch(
            "VF-INFERENCE-044", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Fix the recursive record builder's enclosing type as Schema<?> instead of Object",
            "RecordCodecBuilder.create(",
            "RecordCodecBuilder.<Schema<?>>create(",
        ),
        patch(
            "VF-GENERIC-PATCH-061", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Bridge recursive schema builder fields through their erased App representation",
            "(App<Mu<? extends Schema<?>>, Optional<URI>>)ReferenceUtil.REFERENCE_CODEC.optionalFieldOf",
            "(App<Mu<Schema<?>>, Optional<URI>>)(App<?, ?>)ReferenceUtil.REFERENCE_CODEC.optionalFieldOf",
        ),
        patch(
            "VF-GENERIC-PATCH-062", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Bridge the schema type-list builder field through its erased App representation",
            "(App<Mu<? extends Schema<?>>, List<String>>)ExtraCodecs.compactListCodec(Codec.STRING)",
            "(App<Mu<Schema<?>>, List<String>>)(App<?, ?>)ExtraCodecs.compactListCodec(Codec.STRING)",
        ),
        patch(
            "VF-GENERIC-PATCH-063", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Bridge the recursive items builder field through its erased App representation",
            "(App<Mu<? extends Schema<?>>, Optional<? extends Schema<?>>>)subCodec.optionalFieldOf(\"items\")",
            "(App<Mu<Schema<?>>, Optional<Schema<?>>>)(App<?, ?>)subCodec.optionalFieldOf(\"items\")",
        ),
        patch(
            "VF-GENERIC-PATCH-064", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Bridge the recursive properties builder field through its erased App representation",
            "(App<Mu<? extends Schema<?>>, Map<String, ? extends Schema<?>>>)Codec.unboundedMap(Codec.STRING, subCodec)",
            "(App<Mu<Schema<?>>, Map<String, Schema<?>>>)(App<?, ?>)Codec.unboundedMap(Codec.STRING, subCodec)",
        ),
        patch(
            "VF-GENERIC-PATCH-065", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Bridge the enum-list builder field through its erased App representation",
            "(App<Mu<? extends Schema<?>>, List<String>>)Codec.STRING.listOf().optionalFieldOf(\"enum\"",
            "(App<Mu<Schema<?>>, List<String>>)(App<?, ?>)Codec.STRING.listOf().optionalFieldOf(\"enum\"",
        ),
        patch(
            "VF-GENERIC-PATCH-066", "net/minecraft/server/jsonrpc/methods/DiscoveryService.java",
            "Treat the recursively covariant schema codec as Codec<Schema<?>> at the map boundary",
            "Codec.unboundedMap(Codec.STRING, Schema.CODEC)",
            "Codec.unboundedMap(Codec.STRING, (Codec<Schema<?>>)(Codec<?>)Schema.CODEC)",
        ),
        patch(
            "VF-INFERENCE-045", "net/minecraft/server/jsonrpc/methods/GameRulesService.java",
            "Fix the record-codec builder's enclosing game-rule update type",
            "RecordCodecBuilder.mapCodec(",
            "RecordCodecBuilder.<GameRulesService.GameRuleUpdate<T>>mapCodec(",
        ),
        patch(
            "VF-GENERIC-PATCH-067", "net/minecraft/server/jsonrpc/methods/GameRulesService.java",
            "Give the game-rule type field the concrete update Mu and bridge through erased App",
            "(App<Mu<? extends GameRulesService.GameRuleUpdate<?>>, GameRuleType>)",
            "(App<Mu<GameRulesService.GameRuleUpdate<T>>, GameRuleType>)(App<?, ?>)",
        ),
        patch(
            "VF-GENERIC-PATCH-068", "net/minecraft/server/jsonrpc/methods/GameRulesService.java",
            "Give the game-rule value field the concrete update Mu and bridge through erased App",
            "(App<Mu<? extends GameRulesService.GameRuleUpdate<?>>, T>)",
            "(App<Mu<GameRulesService.GameRuleUpdate<T>>, T>)(App<?, ?>)",
        ),
        patch(
            "VF-GENERIC-PATCH-069", "net/minecraft/util/EncoderCache.java",
            "Return the cached result through the DynamicOps type after copying mutable tags",
            "return value instanceof Tag tag ? tag.copy() : value;",
            "return (T)(value instanceof Tag tag ? tag.copy() : value);",
        ),
        patch(
            "VF-GENERIC-PATCH-070", "net/minecraft/util/datafix/fixes/BlockEntityUUIDFix.java",
            "Use the erased Dynamic fallback required by Optional capture",
            "}).result().map(value -> (Dynamic<?>)value).orElse(tag);",
            "}).result().orElse((Dynamic)tag);",
        ),
        patch(
            "VF-GENERIC-PATCH-071", "net/minecraft/util/datafix/fixes/ChunkBedBlockEntityInjecterFix.java",
            "Express the identity representation conversion through DataFixer's erased function contract",
            "ops -> {\n   return v -> {\n      return v;\n   };\n}",
            "(Function)(ops -> (Function)(v -> v))",
        ),
        patch(
            "VF-GENERIC-PATCH-072", "net/minecraft/util/datafix/fixes/EntitySpawnerItemVariantComponentFix.java",
            "Restore the nested Fixer target type for the salmon bucket method reference",
            "input.updateTyped(componentsFinder, EntitySpawnerItemVariantComponentFix::fixSalmonBucket)",
            "input.updateTyped(componentsFinder, (EntitySpawnerItemVariantComponentFix.Fixer)EntitySpawnerItemVariantComponentFix::fixSalmonBucket)",
        ),
        patch(
            "VF-GENERIC-PATCH-073", "net/minecraft/util/datafix/fixes/EntitySpawnerItemVariantComponentFix.java",
            "Restore the nested Fixer target type for the axolotl bucket method reference",
            "input.updateTyped(componentsFinder, EntitySpawnerItemVariantComponentFix::fixAxolotlBucket)",
            "input.updateTyped(componentsFinder, (EntitySpawnerItemVariantComponentFix.Fixer)EntitySpawnerItemVariantComponentFix::fixAxolotlBucket)",
        ),
        patch(
            "VF-GENERIC-PATCH-074", "net/minecraft/util/datafix/fixes/EntitySpawnerItemVariantComponentFix.java",
            "Restore the nested Fixer target type for the tropical fish bucket method reference",
            "input.updateTyped(componentsFinder, EntitySpawnerItemVariantComponentFix::fixTropicalFishBucket)",
            "input.updateTyped(componentsFinder, (EntitySpawnerItemVariantComponentFix.Fixer)EntitySpawnerItemVariantComponentFix::fixTropicalFishBucket)",
        ),
        patch(
            "VF-GENERIC-PATCH-075", "net/minecraft/util/datafix/fixes/LeavesFix.java",
            "Bridge the rebuilt heterogeneous palette list through its erased finder boundary",
            ".set(this.paletteFinder, this.palette.stream().map(b -> {",
            ".set((OpticFinder)this.paletteFinder, (List)this.palette.stream().map(b -> {",
        ),
        patch(
            "VF-GENERIC-PATCH-076", "net/minecraft/util/datafix/fixes/OptionsKeyLwjgl3Fix.java",
            "Use the erased Dynamic fallback required by Optional capture without changing upstream inference",
            "}).result().map(value -> (Dynamic<?>)value).orElse(tag);",
            "}).result().orElse((com.mojang.serialization.Dynamic)tag);",
        ),
        patch(
            "VF-GENERIC-PATCH-077", "net/minecraft/util/datafix/fixes/OptionsKeyTranslationFix.java",
            "Use the erased Dynamic fallback required by Optional capture without changing upstream inference",
            "}).result().map(value -> (Dynamic<?>)value).orElse(tag);",
            "}).result().orElse((com.mojang.serialization.Dynamic)tag);",
        ),
        patch(
            "VF-GENERIC-PATCH-078", "net/minecraft/world/attribute/EnvironmentAttributeMap.java",
            "Fix the dispatched attribute map's wildcard entry type before xmap construction",
            "Codec.dispatchedMap(EnvironmentAttributes.CODEC, Util.memoize(EnvironmentAttributeMap.Entry::createCodec))",
            "((Codec<Map<EnvironmentAttribute<?>, EnvironmentAttributeMap.Entry<?, ?>>>)(Codec<?>)Codec.dispatchedMap(EnvironmentAttributes.CODEC, Util.memoize(EnvironmentAttributeMap.Entry::createCodec)))",
        ),
        patch(
            "VF-GENERIC-PATCH-078-XMAP", "net/minecraft/world/attribute/EnvironmentAttributeMap.java",
            "Use the constructor directly after fixing the dispatched map's exact value type",
            "xmap(map -> new EnvironmentAttributeMap((Map<EnvironmentAttribute<?>, EnvironmentAttributeMap.Entry<?, ?>>)(Map<?, ?>)map), v -> {",
            "xmap(EnvironmentAttributeMap::new, v -> {",
        ),
        patch(
            "VF-INFERENCE-046", "net/minecraft/world/entity/animal/allay/AllayAi.java",
            "Restore Allay as the generic AnimalPanic behavior type",
            "new AnimalPanic(2.5F)",
            "new AnimalPanic<Allay>(2.5F)",
        ),
        patch(
            "VF-INFERENCE-047", "net/minecraft/world/entity/animal/goat/GoatAi.java",
            "Restore Goat as the generic AnimalPanic behavior type",
            "new AnimalPanic(2.0F)",
            "new AnimalPanic<Goat>(2.0F)",
        ),
        patch(
            "VF-INFERENCE-048", "net/minecraft/world/entity/animal/happyghast/HappyGhastAi.java",
            "Restore HappyGhast as the generic AnimalPanic behavior type",
            "new AnimalPanic(2.0F, 0)",
            "new AnimalPanic<HappyGhast>(2.0F, 0)",
        ),
        patch(
            "VF-INFERENCE-049", "net/minecraft/world/entity/monster/piglin/PiglinAi.java",
            "Restore Piglin and LivingEntity as the raw crossbow behavior's type arguments",
            "new CrossbowAttack()",
            "new CrossbowAttack<Piglin, LivingEntity>()",
        ),
        patch(
            "VF-INFERENCE-050", "net/minecraft/world/item/component/KineticWeapon.java",
            "Give the empty hit branch its EntityHitResult element type",
            "return List.of();",
            "return List.<EntityHitResult>of();",
        ),
        patch(
            "VF-INFERENCE-051", "net/minecraft/world/item/component/PiercingWeapon.java",
            "Give the empty hit branch its EntityHitResult element type",
            "return List.of();",
            "return List.<EntityHitResult>of();",
        ),
        patch(
            "VF-GENERIC-PATCH-079", "net/minecraft/world/level/gamerules/GameRuleMap.java",
            "Fix the dispatched game-rule map value type before applying its object mapping",
            "Codec.dispatchedMap(BuiltInRegistries.GAME_RULE.byNameCodec(), GameRule::valueCodec)",
            "((Codec<Map<GameRule<?>, Object>>)(Codec<?>)Codec.dispatchedMap(BuiltInRegistries.GAME_RULE.byNameCodec(), GameRule::valueCodec))",
        ),
        patch(
            "VF-GENERIC-PATCH-079-XMAP", "net/minecraft/world/level/gamerules/GameRuleMap.java",
            "Use the trusted constructor directly after fixing the dispatched map's exact value type",
            "xmap(map -> GameRuleMap.ofTrusted((Map<GameRule<?>, Object>)(Map<?, ?>)map), GameRuleMap::map)",
            "xmap(GameRuleMap::ofTrusted, GameRuleMap::map)",
        ),
        patch(
            "VF-INFERENCE-052", "net/minecraft/advancements/predicates/entity/PlayerPredicate.java",
            "Give the stat value getter an explicit matcher input type",
            ".forGetter(PlayerPredicate.StatMatcher::value)",
            ".forGetter((PlayerPredicate.StatMatcher<T> matcher) -> matcher.value())",
        ),
        patch(
            "VF-INFERENCE-053", "net/minecraft/advancements/predicates/entity/PlayerPredicate.java",
            "Give the stat range getter an explicit matcher input type",
            ".forGetter(PlayerPredicate.StatMatcher::range)",
            ".forGetter((PlayerPredicate.StatMatcher<T> matcher) -> matcher.range())",
        ),
        patch(
            "VF-GENERIC-PATCH-080", "net/minecraft/client/renderer/item/ConditionalItemModel.java",
            "Bridge the covariant property codec to the method's captured T",
            "contextSwapper.swapTo(originalProperty.type().codec(), originalProperty",
            "contextSwapper.swapTo((com.mojang.serialization.Codec<T>)(com.mojang.serialization.Codec<?>)originalProperty.type().codec(), originalProperty",
        ),
        patch(
            "VF-GENERIC-PATCH-081", "net/minecraft/core/component/predicates/DataComponentPredicate.java",
            "Widen each reconstructed heterogeneous Single entry to Single<?> explicitly",
            "map.entrySet().stream().map(DataComponentPredicate.Single::fromEntry).toList()",
            "map.entrySet().stream().map(entry -> (DataComponentPredicate.Single<?>)DataComponentPredicate.Single.fromEntry(entry)).toList()",
        ),
        patch(
            "VF-GENERIC-PATCH-082", "net/minecraft/server/jsonrpc/IncomingRpcMethods.java",
            "Bridge the untyped game-rule request schema to the builder's concrete erased payload",
            ".param(\"gamerule\", Schema.UNTYPED_GAME_RULE_SCHEMA.asRef())",
            ".param(\"gamerule\", (Schema<GameRulesService.GameRuleUpdate<Object>>)(Schema<?>)Schema.UNTYPED_GAME_RULE_SCHEMA.asRef())",
        ),
        patch(
            "VF-GENERIC-PATCH-083", "net/minecraft/server/jsonrpc/IncomingRpcMethods.java",
            "Bridge the typed game-rule response schema to the builder's concrete erased payload",
            ".response(\"gamerule\", Schema.TYPED_GAME_RULE_SCHEMA.asRef())",
            ".response(\"gamerule\", (Schema<GameRulesService.GameRuleUpdate<Object>>)(Schema<?>)Schema.TYPED_GAME_RULE_SCHEMA.asRef())",
        ),
        patch(
            "VF-INFERENCE-054", "net/minecraft/server/jsonrpc/api/MethodInfo.java",
            "Give the description codec getter its complete MethodInfo input type",
            ".forGetter(MethodInfo::description)",
            ".forGetter((MethodInfo<Params, Result> info) -> info.description())",
        ),
        patch(
            "VF-INFERENCE-055", "net/minecraft/server/jsonrpc/api/MethodInfo.java",
            "Give the params codec getter its complete MethodInfo input type",
            ".forGetter(MethodInfo::params)",
            ".forGetter((MethodInfo<Params, Result> info) -> info.params())",
        ),
        patch(
            "VF-INFERENCE-056", "net/minecraft/server/jsonrpc/api/MethodInfo.java",
            "Give the result codec getter its complete MethodInfo input type",
            ".forGetter(MethodInfo::result)",
            ".forGetter((MethodInfo<Params, Result> info) -> info.result())",
        ),
        patch(
            "VF-INFERENCE-057", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Select Schema<?> as the recursive codec's fixed-point type",
            "Codec.recursive(",
            "Codec.<Schema<?>>recursive(",
        ),
        patch(
            "VF-INFERENCE-058", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Give the reference field getter an explicit Schema<?> input type",
            ".forGetter(Schema::reference)",
            ".forGetter((Schema<?> schema) -> schema.reference())",
        ),
        patch(
            "VF-INFERENCE-059", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Give the type field getter an explicit Schema<?> input type",
            ".forGetter(Schema::type)",
            ".forGetter((Schema<?> schema) -> schema.type())",
        ),
        patch(
            "VF-INFERENCE-060", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Give the items field getter an explicit Schema<?> input type",
            ".forGetter(Schema::items)",
            ".forGetter((Schema<?> schema) -> schema.items())",
        ),
        patch(
            "VF-INFERENCE-061", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Give the properties field getter an explicit Schema<?> input type",
            ".forGetter(Schema::properties)",
            ".forGetter((Schema<?> schema) -> schema.properties())",
        ),
        patch(
            "VF-INFERENCE-062", "net/minecraft/server/jsonrpc/api/Schema.java",
            "Give the enum-values field getter an explicit Schema<?> input type",
            ".forGetter(Schema::enumValues)",
            ".forGetter((Schema<?> schema) -> schema.enumValues())",
        ),
        patch(
            "VF-INFERENCE-063", "net/minecraft/server/jsonrpc/methods/GameRulesService.java",
            "Give the game-rule type getter an explicit update input type",
            ".forGetter(r -> {",
            ".forGetter((GameRulesService.GameRuleUpdate<T> r) -> {",
        ),
        patch(
            "VF-INFERENCE-064", "net/minecraft/server/jsonrpc/methods/GameRulesService.java",
            "Give the game-rule value getter an explicit update input type",
            ".forGetter(GameRulesService.GameRuleUpdate::value)",
            ".forGetter((GameRulesService.GameRuleUpdate<T> update) -> update.value())",
        ),
        patch(
            "VF-GENERIC-PATCH-084", "net/minecraft/core/component/predicates/DataComponentPredicate.java",
            "Bridge the heterogeneous Single list through List<?> after per-entry reconstruction",
            "return map.entrySet().stream().map(entry -> (DataComponentPredicate.Single<?>)DataComponentPredicate.Single.fromEntry(entry)).toList();",
            "return (java.util.List<DataComponentPredicate.Single<?>>)(java.util.List<?>)map.entrySet().stream().map(entry -> DataComponentPredicate.Single.fromEntry(entry)).toList();",
        ),
        patch(
            "VF-INFERENCE-065", "net/minecraft/server/jsonrpc/api/MethodInfo.java",
            "Select Params for the typed parameter codec before field construction",
            "paramsTypedCodec().fieldOf(\"params\")",
            "MethodInfo.<Params>paramsTypedCodec().fieldOf(\"params\")",
        ),
        patch(
            "VF-LAMBDA-PATCH-001", "net/minecraft/client/renderer/blockentity/BeaconRenderer.java",
            "Capture the solid-beam geometry values before Vineflower's reused locals are reassigned",
            "submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(beamLocation, false), (pose, buffer) -> {\n   renderPart(pose, buffer, color, beamStart, beamEnd, 0.0F, wnz, enx, 0.0F, wsx, 0.0F, 0.0F, esz, 0.0F, 1.0F, vv1, vv2);",
            "float solidWnz = wnz;\n       float solidEnx = enx;\n       float solidWsx = wsx;\n       float solidEsz = esz;\n       float solidVv1 = vv1;\n       float solidVv2 = vv2;\n       submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(beamLocation, false), (pose, buffer) -> {\n          renderPart(pose, buffer, color, beamStart, beamEnd, 0.0F, solidWnz, solidEnx, 0.0F, solidWsx, 0.0F, 0.0F, solidEsz, 0.0F, 1.0F, solidVv1, solidVv2);",
        ),
        patch(
            "VF-LAMBDA-PATCH-002", "net/minecraft/client/renderer/blockentity/BeaconRenderer.java",
            "Capture the glow-beam geometry values after Vineflower's reused-local assignments",
            "submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(beamLocation, true), (pose, buffer) -> {\n   renderPart(pose, buffer, ARGB.color(32, color), beamStart, beamEnd, wnx, wnz, enx, enz, wsx, wsz, esx, esz, 0.0F, 1.0F, vv1, vv2);",
            "float glowWnx = wnx;\n       float glowWnz = wnz;\n       float glowEnx = enx;\n       float glowEnz = enz;\n       float glowWsx = wsx;\n       float glowWsz = wsz;\n       float glowEsx = esx;\n       float glowEsz = esz;\n       float glowVv1 = vv1;\n       float glowVv2 = vv2;\n       submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(beamLocation, true), (pose, buffer) -> {\n          renderPart(pose, buffer, ARGB.color(32, color), beamStart, beamEnd, glowWnx, glowWnz, glowEnx, glowEnz, glowWsx, glowWsz, glowEsx, glowEsz, 0.0F, 1.0F, glowVv1, glowVv2);",
        ),
        patch(
            "VF-SWITCH-001", "net/minecraft/util/CommonLinks.java",
            "Restore the NONE enum arm that leaves the Realms extension URL unchanged",
            "case EXPIRED_TRIAL -> uri + \"&ref=expiredTrial\";",
            "case NONE -> uri;\n            case EXPIRED_TRIAL -> uri + \"&ref=expiredTrial\";",
        ),
    )

    private fun patch(id: String, path: String, description: String, expected: String, replacement: String) =
        ExactSourcePatch(id, path, description, expected, replacement, "26.3-rc-2")
}
