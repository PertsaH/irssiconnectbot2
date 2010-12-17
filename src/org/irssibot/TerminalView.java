package org.irssibot;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Message;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.VDUDisplay;
import org.irssibot.transport.PromptMessage;
import org.irssibot.transport.Transport;
import org.irssibot.util.Colors;

import static org.irssibot.util.LogHelper.DEBUG;

/**
 * User: parkerkane
 * Date: 13.12.2010
 * Time: 9:54
 */
public class TerminalView extends BaseTerminalView implements VDUDisplay {

	private Paint defaultPaint;
	private int   charWidth;
	private int   charHeight;

	private Canvas terminalCanvas;
	private Bitmap terminalBitmap;

	private boolean fullRedraw = false;

	KeyCharacterMap keymap = KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD);
	private int                charTop;
	private InputMethodManager inputManager;

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND;
		outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT;

		return super.onCreateInputConnection(outAttrs);
	}

	public TerminalView(Context context, final Transport transport) {

		super(context, transport);

		WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
		lp.width = WindowManager.LayoutParams.MATCH_PARENT;
		lp.height = WindowManager.LayoutParams.MATCH_PARENT;
		DEBUG(lp);
		setLayoutParams(lp);

		terminalCanvas = new Canvas();

		buffer.setDisplay(this);

		transport.setPromptHandler(new PromptHandler());

		transport.connect();

		inputManager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);

		defaultPaint = new Paint();

		defaultPaint.setAntiAlias(true);
		defaultPaint.setTypeface(Typeface.MONOSPACE);
		defaultPaint.setFakeBoldText(true);

		setFontSize(10);

		setFocusable(true);
		setFocusableInTouchMode(true);

		setOnKeyListener(new TerminalKeyListener(this, transport, buffer));

		requestFocus();

		setOnClickListener(new OnClickListener() {

			public void onClick(View v) {
				inputManager.showSoftInput(TerminalView.this, InputMethodManager.SHOW_FORCED);
			}
		});
	}

	@Override
	protected void onSizeChanged(int width, int height, int oldw, int oldh) {
		super.onSizeChanged(width, height, oldw, oldh);

		DEBUG("Screen size changed:", width, height, oldw, oldh);

		if (terminalBitmap != null) {
			terminalBitmap.recycle();
		}

		terminalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

		terminalCanvas = new Canvas(terminalBitmap);

		int terminalWidth = (int) Math.ceil(width / charWidth);
		int terminalHeight = (int) Math.ceil(height / charHeight);

		transport.resize(terminalWidth, terminalHeight, width, height);

		buffer.setScreenSize(terminalWidth, terminalHeight, true);

		redraw();
	}

	@Override
	protected void onDraw(Canvas canvas) {

		DEBUG("Draw screen.");

//		super.onDraw(terminalCanvas);

		synchronized (buffer) {

			boolean entireDirty = buffer.update[0] || fullRedraw;

			int bg, fg, fgColor, bgColor;

			for (int y = 0; y < buffer.height; y++) {

				if (!buffer.update[y + 1] && !entireDirty) {
					continue;
				}

				buffer.update[y + 1] = false;

				for (int x = 0; x < buffer.width; x++) {

					int addr = 0;
					int curAttr = buffer.charAttributes[buffer.windowBase + y][x];

					while (x + addr < buffer.width &&
						   buffer.charAttributes[buffer.windowBase + y][x + addr] == curAttr) {
						addr++;
					}


					if ((curAttr & VDUBuffer.COLOR_FG) != 0) {
						fgColor = ((curAttr & VDUBuffer.COLOR_FG) >> VDUBuffer.COLOR_FG_SHIFT) - 1;
					} else {
						fgColor = 15;
						
					}

					if (fgColor < 8 && (curAttr & VDUBuffer.BOLD) != 0) {
						fg = Colors.defaults[fgColor + 8];
					} else {
						fg = Colors.defaults[fgColor];
					}

					// check if background color attribute is set
					if ((curAttr & VDUBuffer.COLOR_BG) != 0) {
						bg = Colors.defaults[((curAttr & VDUBuffer.COLOR_BG) >> VDUBuffer.COLOR_BG_SHIFT) - 1];
					} else {
						bg = Colors.defaults[0];
					}

					defaultPaint.setColor(bg);

					terminalCanvas.save(Canvas.CLIP_SAVE_FLAG);

					terminalCanvas.clipRect(
						x * charWidth, y * charHeight,
						(x + addr) * charWidth, (y + 1) * charHeight

					);

					terminalCanvas.drawPaint(defaultPaint);

					defaultPaint.setColor(fg);

					terminalCanvas.drawText(
						buffer.charArray[buffer.windowBase + y],
						x,
						addr,
						x * charWidth,
						(y * charHeight) - charTop,
						defaultPaint);

					terminalCanvas.restore();

					x += addr - 1;
				}

			}

			buffer.update[0] = false;

			int cursorColumn = buffer.getCursorColumn();
			int cursorRow = buffer.getCursorRow();

			defaultPaint.setColor(0x3FFFFFFF);

			terminalCanvas.save(Canvas.CLIP_SAVE_FLAG);

			terminalCanvas.clipRect(
				cursorColumn * charWidth, cursorRow * charHeight,
				(cursorColumn + 1) * charWidth, (cursorRow + 1) * charHeight
			);

			terminalCanvas.drawPaint(defaultPaint);

			terminalCanvas.restore();

		}

		fullRedraw = false;

		canvas.drawBitmap(terminalBitmap, 0, 0, null);
	}

	public void setFontSize(float size) {
		defaultPaint.setTextSize(size);

		Paint.FontMetrics fm = defaultPaint.getFontMetrics();

		float[] widths = new float[1];
		defaultPaint.getTextWidths("X", widths);

		charWidth = (int) Math.ceil(widths[0]);
		charHeight = (int) Math.ceil(fm.descent - fm.top);

		charTop = (int) Math.ceil(fm.top);

		redraw();
	}

	public VDUBuffer getVDUBuffer() {
		return null;
	}

	public void redraw() {
		invalidate();
		fullRedraw = true;
	}

	public void updateScrollBar() {
	}

	public void setVDUBuffer(VDUBuffer buffer) {
	}

	public void setColor(int index, int red, int green, int blue) {
	}

	public void resetColors() {
	}

	private class PromptHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);

			final PromptMessage data = (PromptMessage) msg.obj;

			DEBUG("Got prompt message:", data.type);

			switch (data.type) {
				case Message:
					Toast.makeText(TerminalView.this.context, data.message, Toast.LENGTH_LONG).show();
					data.release();
					break;

				case Boolean:

					AlertDialog.Builder builder = new AlertDialog.Builder(TerminalView.this.context)
						.setCancelable(false)
						.setTitle("SSH")
						.setMessage(data.message)
						.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {

							public void onClick(DialogInterface dialog, int which) {
								data.sendResponse(true);
							}
						})
						.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {

							public void onClick(DialogInterface dialog, int which) {
								data.sendResponse(false);
							}
						});

					builder.create().show();

					break;

				case String:

					// TODO: Need to be done!

					data.release();
					break;

				case Password:

					final Dialog dialog = new Dialog(TerminalView.this.context);

					dialog.setTitle(data.message);
					dialog.setContentView(R.layout.password);

					final EditText password = (EditText) dialog.findViewById(R.id.password);

					password.setText("");
					password.setOnKeyListener(new OnKeyListener() {

						public boolean onKey(View v, int keyCode, KeyEvent event) {

							if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
								data.sendResponse(password.getText().toString());

								dialog.dismiss();
								return true;
							}

							return false;
						}
					});

					dialog.show();
					break;

			}
		}
	}
}
