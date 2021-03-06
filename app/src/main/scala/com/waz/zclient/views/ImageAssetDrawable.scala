/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.views

import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.content.Context
import android.graphics._
import android.graphics.drawable.Drawable
import android.net.Uri
import android.renderscript.{Allocation, Element, RenderScript, ScriptIntrinsicBlur}
import com.waz.model.AssetData.{IsImage, IsVideo}
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.assets.AssetService.BitmapResult.{BitmapLoaded, LoadingFailed}
import com.waz.service.images.BitmapSignal
import com.waz.threading.Threading
import com.waz.ui.MemoryImageCache.BitmapRequest
import com.waz.ui.MemoryImageCache.BitmapRequest.Regular
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.utils.Offset
import com.waz.zclient.views.ImageAssetDrawable.{RequestBuilder, ScaleType, State}
import com.waz.zclient.views.ImageController._
import com.waz.zclient.{Injectable, Injector}
import com.waz.ZLog.ImplicitTag._

//TODO could merge with logic from the ChatheadView to make a very general drawable for our app
class ImageAssetDrawable(
                          src: Signal[ImageSource],
                          scaleType: ScaleType = ScaleType.FitXY,
                          request: RequestBuilder = RequestBuilder.Regular,
                          background: Option[Drawable] = None
                        )(implicit inj: Injector, eventContext: EventContext) extends Drawable with Injectable {

  val images = inject[ImageController]

  private val matrix = new Matrix()
  private val dims = Signal[Dim2]()
  private val bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG)

  private val animator = ValueAnimator.ofFloat(0, 1).setDuration(750)

  val padding = Signal(Offset.Empty)

  animator.addUpdateListener(new AnimatorUpdateListener {
    override def onAnimationUpdate(animation: ValueAnimator): Unit = {
      val alpha = (animation.getAnimatedFraction * 255).toInt
      bitmapPaint.setAlpha(alpha)
      invalidateSelf()
    }
  })

  val state = (for {
    im <- src
    d <- dims if d.width > 0
    p <- padding
    state <- bitmapState(im, d.width - p.l - p.r)
  } yield state).disableAutowiring()

  state.on(Threading.Ui) { st =>
    invalidateSelf()
  }

  background foreach { bg =>
    dims.zip(padding) { case (_, Offset(l, t, r, b)) =>
      val bounds = getBounds
      bg.setBounds(bounds.left + l, bounds.top + t, bounds.right - r, bounds.bottom - b)
    }
  }

  private def bitmapState(im: ImageSource, w: Int) =
    images.imageSignal(im, request(w))
      .map[State] {
        case BitmapLoaded(bmp, etag) => State.Loaded(im, Some(bmp), etag)
        case LoadingFailed(ex) => State.Failed(im, Some(ex))
        case _ => State.Failed(im)
      }
      .orElse(Signal const State.Loading(im))

  // previously drawn state
  private var prev = Option.empty[State]

  override def draw(canvas: Canvas): Unit = {

    // will only use fadeIn if we previously displayed an empty bitmap
    // this way we can avoid animating if view was recycled
    def resetAnimation(state: State) = {
      animator.end()
      if (state.bmp.nonEmpty && prev.exists(_.bmp.isEmpty)) animator.start()
    }

    def updateMatrix(b: Bitmap) = {
      val bounds = getBounds
      val p = padding.currentValue.getOrElse(Offset.Empty)
      scaleType(matrix, b.getWidth, b.getHeight, Dim2(bounds.width() - p.l - p.r, bounds.height() - p.t - p.b))
      matrix.postTranslate(bounds.left + p.l, bounds.top + p.t)
    }

    def updateDrawingState(state: State) = {
      state.bmp foreach updateMatrix
      if (prev.forall(p => p.src != state.src || p.bmp.isEmpty != state.bmp.isEmpty)) resetAnimation(state)
    }

    state.currentValue foreach { st =>
      if (!prev.contains(st)) {
        updateDrawingState(st)
        prev = Some(st)
      }

      if (st.bmp.isEmpty || bitmapPaint.getAlpha < 255)
        background foreach { _.draw(canvas) }

      st.bmp foreach { bm =>
        drawBitmap(canvas, bm, matrix, bitmapPaint)
      }
    }
  }

  protected def drawBitmap(canvas: Canvas, bm: Bitmap, matrix: Matrix, bitmapPaint: Paint): Unit ={
    canvas.drawBitmap(bm, matrix, bitmapPaint)
  }

  override def onBoundsChange(bounds: Rect): Unit = {
    dims ! Dim2(bounds.width(), bounds.height())
  }

  override def setColorFilter(colorFilter: ColorFilter): Unit = {
    bitmapPaint.setColorFilter(colorFilter)
    invalidateSelf()
  }

  override def setAlpha(alpha: Int): Unit = {
    bitmapPaint.setAlpha(alpha)
    invalidateSelf()
  }

  override def getOpacity: Int = PixelFormat.TRANSLUCENT

  override def getIntrinsicHeight: Int = dims.currentValue.map(_.height).getOrElse(-1)

  override def getIntrinsicWidth: Int = dims.currentValue.map(_.width).getOrElse(-1)
}

object ImageAssetDrawable {

  sealed trait ScaleType {
    def apply(matrix: Matrix, w: Int, h: Int, viewSize: Dim2): Unit
  }
  object ScaleType {
    case object FitXY extends ScaleType {
      override def apply(matrix: Matrix, w: Int, h: Int, viewSize: Dim2): Unit =
        matrix.setScale(viewSize.width.toFloat / w, viewSize.height.toFloat / h)
    }
    case object FitY extends ScaleType {
      override def apply(matrix: Matrix, w: Int, h: Int, viewSize: Dim2): Unit = {
        val scale = viewSize.height.toFloat / h
        matrix.setScale(scale, scale)
        matrix.postTranslate(- (w * scale - viewSize.width) / 2, 0)
      }
    }
    case object CenterCrop extends ScaleType {
      override def apply(matrix: Matrix, w: Int, h: Int, viewSize: Dim2): Unit = {
        val scale = math.max(viewSize.width.toFloat / w, viewSize.height.toFloat / h)
        val dx = - (w * scale - viewSize.width) / 2
        val dy = - (h * scale - viewSize.height) / 2

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
      }
    }
    case object CenterInside extends ScaleType {
      override def apply(matrix: Matrix, w: Int, h: Int, viewSize: Dim2): Unit = {
        val scale = math.min(viewSize.width.toFloat / w, viewSize.height.toFloat / h)
        val dx = - (w * scale - viewSize.width) / 2
        val dy = - (h * scale - viewSize.height) / 2

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
      }
    }
  }

  type RequestBuilder = Int => BitmapRequest
  object RequestBuilder {
    val Regular: RequestBuilder = BitmapRequest.Regular(_)
    val Single: RequestBuilder = BitmapRequest.Single(_)
    val Round: RequestBuilder = BitmapRequest.Round(_)
  }

  sealed trait State {
    val src: ImageSource
    val bmp: Option[Bitmap] = None
  }
  object State {
    case class Loading(src: ImageSource) extends State
    case class Loaded(src: ImageSource, override val bmp: Option[Bitmap], etag: Int = 0) extends State
    case class Failed(src: ImageSource, ex: Option[Throwable] = None) extends State
  }
}

class RoundedImageAssetDrawable (
                                  src: Signal[ImageSource],
                                  scaleType: ScaleType = ScaleType.FitXY,
                                  request: RequestBuilder = RequestBuilder.Regular,
                                  background: Option[Drawable] = None,
                                  cornerRadius: Float = 0
                                )(implicit inj: Injector, eventContext: EventContext) extends ImageAssetDrawable(src, scaleType, request, background) {

  override protected def drawBitmap(canvas: Canvas, bm: Bitmap, matrix: Matrix, bitmapPaint: Paint): Unit = {
    val shader = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(matrix)
    val rect = new RectF(0.0f, 0.0f, getBounds.width, getBounds.height)
    bitmapPaint.setShader(shader)
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bitmapPaint)
  }
}

class BlurredImageAssetDrawable(
                                 src: Signal[ImageSource],
                                 scaleType: ScaleType = ScaleType.FitXY,
                                 request: RequestBuilder = RequestBuilder.Regular,
                                 background: Option[Drawable] = None,
                                 blurRadius: Float = 0,
                                 context: Context
                               )(implicit inj: Injector, eventContext: EventContext) extends ImageAssetDrawable(src, scaleType, request, background) {

  override protected def drawBitmap(canvas: Canvas, bm: Bitmap, matrix: Matrix, bitmapPaint: Paint): Unit = {
    val renderScript = RenderScript.create(context)
    val blurInput = Allocation.createFromBitmap(renderScript, bm)
    val blurOutput = Allocation.createFromBitmap(renderScript, bm)

    val blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
    blur.setInput(blurInput)
    blur.setRadius(blurRadius)
    blur.forEach(blurOutput)

    blurOutput.copyTo(bm)

    blurInput.destroy()
    blurOutput.destroy()
    renderScript.destroy()

    canvas.drawBitmap(bm, matrix, bitmapPaint)
  }

}

class ImageController(implicit inj: Injector) extends Injectable {

  val zMessaging = inject[Signal[ZMessaging]]

  def imageData(id: AssetId) = zMessaging.flatMap {
    zms => zms.assetsStorage.signal(id).flatMap {
      case a@IsImage() => Signal.const(a)
      case a@IsVideo() => a.previewId.fold(Signal.const(AssetData.Empty))(zms.assetsStorage.signal)
      case _ => Signal.const(AssetData.Empty)
    }
  }

  def imageSignal(id: AssetId, width: Int): Signal[BitmapResult] =
    imageSignal(id, Regular(width))

  def imageSignal(id: AssetId, req: BitmapRequest): Signal[BitmapResult] =
    for {
      zms <- zMessaging
      data <- imageData(id)
      res <- BitmapSignal(data, req, zms.imageLoader, zms.imageCache)
    } yield res

  def imageSignal(uri: Uri, req: BitmapRequest): Signal[BitmapResult] =
    BitmapSignal(AssetData(source = Some(uri)), req, ZMessaging.currentGlobal.imageLoader, ZMessaging.currentGlobal.imageCache)

  def imageSignal(data: AssetData, req: BitmapRequest): Signal[BitmapResult] =
    zMessaging flatMap { zms => BitmapSignal(data, req, zms.imageLoader, zms.imageCache) }

  def imageSignal(src: ImageSource, req: BitmapRequest): Signal[BitmapResult] = src match {
    case WireImage(id) => imageSignal(id, req)
    case ImageUri(uri) => imageSignal(uri, req)
    case DataImage(data) => imageSignal(data, req)
  }
}

object ImageController {

  sealed trait ImageSource
  case class WireImage(id: AssetId) extends ImageSource
  case class DataImage(data: AssetData) extends ImageSource
  case class ImageUri(uri: Uri) extends ImageSource
}
