package tech.illusion.todayinhistory.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.math.Vector3
import tech.illusion.todayinhistory.R
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeVolume() {
    SpatialView(
        modifier = Modifier,
        initial = { content, attachments ->
            // You can replace the model in Spatial Editor using its built-in assets library or with
            // your own custom model.
            val bundle =
                withContext(Dispatchers.IO) { AssetBundle.load("asset://editor-asset.bundle") }
            val model = Entity.loadSuspend(modelName = "MyScene", bundle = bundle)
            // Close the bundle when finished loading.
            bundle.close()
            model.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, -0.17f, 0f))
                    scaleBy(1.2f)
                }
                content.addEntity(this)
            }
            val bodyTextAttachment = attachments.entity(id = "homepage_body")
            bodyTextAttachment?.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, 0.1f, -0.2f))
                }
                content.addEntity(this)
            }
            val titleTextAttachment = attachments.entity(id = "homepage_title")
            titleTextAttachment?.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, -0.35f, 0.3f))
                }
                content.addEntity(this)
            }
        },
        attachments = {
            AttachmentPanel(id = "homepage_body") {
                Box(
                    modifier =
                        Modifier.size(600.dp, 194.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .backgroundMaterial(true, Material.Regular)
                ) {
                    Text(
                        modifier = Modifier.padding(32.dp),
                        text = stringResource(R.string.homepage_body),
                        style = PicoTheme.typography.titleLarge,
                        fontSize = 20.sp,
                        color = Color(0x80000000)
                    )
                }
            }
            AttachmentPanel(id = "homepage_title") {
                Box(
                    modifier =
                        Modifier.size(388.dp, 64.dp)
                            .clip(RoundedCornerShape(55.dp))
                            .backgroundMaterial(true, Material.Regular),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.homepage_title),
                        style = PicoTheme.typography.headlineLarge,
                        fontSize = 24.sp,
                        color = Color(0xCC000000)
                    )
                }
            }
        }
    )
}