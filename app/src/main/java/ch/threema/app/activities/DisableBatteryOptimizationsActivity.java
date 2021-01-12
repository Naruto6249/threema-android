/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2016-2021 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.activities;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.RuntimeUtil;

import static ch.threema.app.fragments.BackupDataFragment.REQUEST_ID_DISABLE_BATTERY_OPTIMIZATIONS;

/**
 * Guides user through the process of disabling battery optimization energy saving option.
 */

public class DisableBatteryOptimizationsActivity extends AppCompatActivity implements GenericAlertDialog.DialogClickListener {
	private static final Logger logger = LoggerFactory.getLogger(DisableBatteryOptimizationsActivity.class);

	private static final int REQUEST_CODE_IGNORE_BATTERY_OPTIMIZATIONS = 778;
	private static final String DIALOG_TAG_DISABLE_BATTERY_OPTIMIZATIONS = "des";
	private static final String DIALOG_TAG_BATTERY_OPTIMIZATIONS_REMINDER = "esr";

	public static final String EXTRA_NAME = "name";
	public static final String EXTRA_CONFIRM = "confirm";
	public static final String EXTRA_CANCEL_LABEL = "cancel";
	public static final String EXTRA_WIZARD = "wizard";

	private String name;
	@StringRes private int cancelLabel;
	private boolean confirm;
	private int actionBarSize = 0;
	private Handler dropDownHandler, listSelectHandler;

	@Override
	@TargetApi(Build.VERSION_CODES.M)
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isWhitelisted(this)) {
			setResult(RESULT_OK);
			finish();
			return;
		}

		Intent intent = getIntent();

		if (ConfigUtils.getAppTheme(this) == ConfigUtils.THEME_DARK || intent.getBooleanExtra(EXTRA_WIZARD, false)) {
			setTheme(R.style.Theme_Threema_Translucent_Dark);
		}

		if (ConfigUtils.checkManifestPermission(this, getPackageName(), "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
			@SuppressLint("BatteryLife") Intent newIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + BuildConfig.APPLICATION_ID));

			try {
				startActivityForResult(newIntent, REQUEST_ID_DISABLE_BATTERY_OPTIMIZATIONS);
				return;
			} catch (ActivityNotFoundException e) {
				// Some Samsung devices don't bother implementing this API
				logger.error("Exception", e);
				setResult(RESULT_OK);
				finish();
				return;
			}
		}

		name = intent.getStringExtra(EXTRA_NAME);
		confirm = intent.getBooleanExtra(EXTRA_CONFIRM, false);
		cancelLabel = intent.getIntExtra(EXTRA_CANCEL_LABEL, R.string.continue_anyway);

		actionBarSize = ConfigUtils.getActionBarSize(this);

		showDisableDialog();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
	}

	private void showDisableDialog() {
		GenericAlertDialog dialog = GenericAlertDialog.newInstance(R.string.battery_optimizations_title, String.format(getString(R.string.battery_optimizations_explain), name, getString(R.string.app_name)), R.string.disable, cancelLabel);
		dialog.show(getSupportFragmentManager(), DIALOG_TAG_DISABLE_BATTERY_OPTIMIZATIONS);
	}

	public static boolean isWhitelisted(Context context) {
		// app is always whitelited in unit tests
		if (RuntimeUtil.isInTest()) {
			return true;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			PowerManager powerManager = (PowerManager) context.getApplicationContext().getSystemService(POWER_SERVICE);
			try {
				return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
			} catch (Exception e) {
				// don't care about buggy phones not implementing this API
				return true;
			}
		}
		return true;
	}

	@TargetApi(Build.VERSION_CODES.M)
	@Override
	public void onYes(String tag, Object data) {
		switch (tag) {
			case DIALOG_TAG_DISABLE_BATTERY_OPTIMIZATIONS:
				Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
				// Samsuck Galaxy S5 with API 23 does not know this intent
				if (intent.resolveActivity(getPackageManager()) != null) {
					startActivityForResult(intent, REQUEST_CODE_IGNORE_BATTERY_OPTIMIZATIONS);

					dropDownHandler = new Handler();
					dropDownHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							Toast toast = Toast.makeText(getApplicationContext(), R.string.battery_optimizations_disable_guide, Toast.LENGTH_LONG);
							toast.setGravity(Gravity.TOP| Gravity.LEFT, 0, actionBarSize * 2);
							toast.show();
						}
					}, 2 * DateUtils.SECOND_IN_MILLIS);

					listSelectHandler = new Handler();
					listSelectHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							Toast ctdToast = Toast.makeText(getApplicationContext(), String.format(getString(R.string.battery_optimizations_disable_guide_ctd), getString(R.string.app_name)), Toast.LENGTH_LONG);
							ctdToast.setGravity(Gravity.CENTER, 0, 0);
							ctdToast.show();
						}
					}, 8 * DateUtils.SECOND_IN_MILLIS);
				}
				break;
			case DIALOG_TAG_BATTERY_OPTIMIZATIONS_REMINDER:
				// user wants to continue at his own risk
				setResult(RESULT_OK);
				finish();
				break;
		}
	}

	@Override
	public void onNo(String tag, Object data) {
		switch (tag) {
			case DIALOG_TAG_DISABLE_BATTERY_OPTIMIZATIONS:
				if (confirm) {
					GenericAlertDialog dialog = GenericAlertDialog.newInstance(R.string.battery_optimizations_title, String.format(getString(R.string.battery_optimizations_disable_confirm), getString(R.string.app_name), name), R.string.yes, R.string.no);
					dialog.show(getSupportFragmentManager(), DIALOG_TAG_BATTERY_OPTIMIZATIONS_REMINDER);
				} else {
					setResult(RESULT_CANCELED);
					finish();
				}
				break;
			case DIALOG_TAG_BATTERY_OPTIMIZATIONS_REMINDER:
				showDisableDialog();
				break;
		}
	}

	private void removeHandlers() {
		if (dropDownHandler != null) {
			dropDownHandler.removeCallbacksAndMessages(null);
		}
		if (listSelectHandler != null) {
			listSelectHandler.removeCallbacksAndMessages(null);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
			case REQUEST_ID_DISABLE_BATTERY_OPTIMIZATIONS:
				// back from system dialog
				if (isWhitelisted(this)) {
					setResult(RESULT_OK);
				} else {
					setResult(RESULT_CANCELED);
				}
				finish();
				break;
			case REQUEST_CODE_IGNORE_BATTERY_OPTIMIZATIONS:
				// backup from overlay hack
				removeHandlers();
				if (isWhitelisted(this)) {
					setResult(RESULT_OK);
					finish();
				} else {
					showDisableDialog();
				}
				break;
			default:
				break;
		}
	}

	@Override
	public void finish() {
		// used to avoid flickering of status and navigation bar when activity is closed
		super.finish();
		overridePendingTransition(0, 0);
	}
}
