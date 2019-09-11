package org.pirate.sevenseas

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.beust.klaxon.Klaxon
import kotlinx.android.synthetic.main.activity_send.*
import kotlinx.android.synthetic.main.content_send.*
import java.text.DecimalFormat


class SendActivity : AppCompatActivity() {

    private val REQUEST_CONFIRM = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)
        setSupportActionBar(toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        title = "Send Transaction"

        // Clear the valid address prompt
        txtValidAddress.text = ""
        txtSendCurrencySymbol.text = ""

        if (intent.getStringExtra("address") != null)
            sendAddress.setText(intent.getStringExtra("address"), TextView.BufferType.EDITABLE)

        if (intent.getDoubleExtra("amount", -1.0) > 0)
            setAmountUSD(intent.getDoubleExtra("amount", 0.0))

        if (intent.getBooleanExtra("includeReplyTo", false))
            chkIncludeReplyTo.isChecked = true

        imageButton.setOnClickListener { view ->
            val intent = Intent(this, QrReaderActivity::class.java)
            intent.putExtra("REQUEST_CODE",
                QrReaderActivity.REQUEST_ADDRESS
            )
            startActivityForResult(intent,
                QrReaderActivity.REQUEST_ADDRESS
            )
        }

        sendAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            @SuppressLint("SetTextI18n")
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (DataModel.isValidAddress(s.toString())) {
                    txtValidAddress.text = "\u2713 Valid address"
                    txtValidAddress.setTextColor(ContextCompat.getColor(applicationContext,
                        R.color.colorPrimary
                    ))
                } else {
                    txtValidAddress.text = "Not a valid address"
                    txtValidAddress.setTextColor(ContextCompat.getColor(applicationContext,
                        R.color.colorAccent
                    ))
                }

                if (s?.startsWith("t") == true) {
                    txtSendMemo.isEnabled = false
                    chkIncludeReplyTo.isEnabled = false
                    txtSendMemo.text = SpannableStringBuilder("")
                    txtSendMemoTitle.text = "(No Memo for t-Addresses)"
                } else {
                    txtSendMemo.isEnabled = true
                    chkIncludeReplyTo.isEnabled = true
                    txtSendMemoTitle.text = "Memo (Optional)"
                }
            }
        })

        amountUSD.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            @SuppressLint("SetTextI18n")
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val usd = s.toString().toDoubleOrNull()
                val zprice = DataModel.mainResponseData?.zecprice
                //val zprice = 0.4

                if (usd == null) {
                    txtSendCurrencySymbol.text = "" // Let the placeholder show the "$" sign
                } else {
                    txtSendCurrencySymbol.text = "$"
                }

                if (usd == null || zprice == null)
                    amountARRR.text = "${DataModel.mainResponseData?.tokenName} 0.0"
                else
                    amountARRR.text =
                        "${DataModel.mainResponseData?.tokenName} " + DecimalFormat("#.########").format(usd / zprice)
            }
        })

        txtSendMemo.imeOptions = EditorInfo.IME_ACTION_DONE
        txtSendMemo.setRawInputType(InputType.TYPE_CLASS_TEXT)

        txtSendMemo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                txtMemoSize.text = "${s?.length ?: 0} / 512"
                if (s?.length ?: 0 > 512) {
                    txtMemoSize.setTextColor(ContextCompat.getColor(applicationContext,
                        R.color.colorAccent
                    ))
                } else {
                    txtMemoSize.setTextColor(ContextCompat.getColor(applicationContext,
                        R.color.colorPrimary
                    ))
                }
            }
        })

        btnSend.setOnClickListener { view ->
            doValidationsThenConfirm()
        }
    }

    private fun doValidationsThenConfirm()  {
        // First, check if the address is correct.
        val toAddr = sendAddress.text.toString()
        if (!DataModel.isValidAddress(toAddr)) {
            showErrorDialog("Invalid destination address!")
            return
        }

        // Then if the amount is valid
        val amt = amountARRR.text.toString()
        val parsedAmt = amt.substring("${DataModel.mainResponseData?.tokenName} ".length, amt.length)
        if (parsedAmt.toDoubleOrNull() == 0.0 || parsedAmt.toDoubleOrNull() == null) {
            showErrorDialog("Invalid amount!")
            return
        }

        println("Maxzspendable ${DataModel.mainResponseData?.maxzspendable}")

        // Check if this is more than the maxzspendable
        if (DataModel.mainResponseData?.maxzspendable != null) {
            if (parsedAmt.toDouble() > DataModel.mainResponseData?.maxzspendable!! &&
                parsedAmt.toDouble() <= DataModel.mainResponseData?.maxspendable ?: Double.MAX_VALUE) {

                val alertDialog = AlertDialog.Builder(this@SendActivity)
                alertDialog.setTitle("Send from t-addr?")
                alertDialog.setMessage("${DataModel.mainResponseData?.tokenName} $parsedAmt is more than the balance in " +
                        "your sapling address. This Tx will have to be sent from a transparent address, and will" +
                        " not be private.\n\nAre you absolutely sure?")
                alertDialog.apply {
                    setPositiveButton("Send Anyway") { dialog, id -> doConfirm() }
                    setNegativeButton("Cancel") { dialog, id -> dialog.cancel() }
                }

                alertDialog.create().show()
                return
            }
        }

        // Warning if spending more than total
        if (parsedAmt.toDouble() > DataModel.mainResponseData?.maxspendable ?: Double.MAX_VALUE) {
            showErrorDialog("Can't spend more than ${DataModel.mainResponseData?.tokenName} " +
                    "${DataModel.mainResponseData?.maxspendable} in a single Tx")
            return
        }

        val memo = txtSendMemo.text.toString() + getReplyToAddressIfChecked(toAddr)
        if (memo.length > 512) {
            showErrorDialog("Memo is too long")
            return
        }

        if (toAddr.startsWith("t") && !memo.isBlank()) {
            showErrorDialog("Can't send a memo to a t-Address")
            return
        }

        doConfirm()
    }

    private fun doConfirm() {
        val toAddr = sendAddress.text.toString()
        val amt = amountARRR.text.toString()
        val parsedAmt = amt.substring("${DataModel.mainResponseData?.tokenName} ".length, amt.length)
        val memo = txtSendMemo.text.toString() + getReplyToAddressIfChecked(toAddr)

        val intent = Intent(this, TxDetailsActivity::class.java)
        val tx = DataModel.TransactionItem(
            "confirm", 0, parsedAmt, memo,
            toAddr, "", 0
        )
        intent.putExtra("EXTRA_TXDETAILS", Klaxon().toJsonString(tx))
        startActivityForResult(intent, REQUEST_CONFIRM)
    }

    private fun getReplyToAddressIfChecked(toAddr: String) : String {
        if (chkIncludeReplyTo.isChecked && !toAddr.startsWith("t")) {
            return "\nReply to:\n${DataModel.mainResponseData?.saplingAddress}"
        } else {
            return ""
        }
    }

    fun showErrorDialog(msg: String) {
        val alertDialog = AlertDialog.Builder(this@SendActivity).create()
        alertDialog.setTitle("Error Sending Transaction")
        alertDialog.setMessage(msg)
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK") {
                dialog, _ -> dialog.dismiss() }
        alertDialog.show()
    }

    private fun Double.format(digits: Int): String? = java.lang.String.format("%.${digits}f", this)

    private fun setAmountUSD(amt: Double) {
        amountUSD.setText(amt.format(2))
        setAmount(amt / (DataModel.mainResponseData?.zecprice ?: 0.0))
    }

    private fun setAmountArrr(amt: Double?) {
        if (amt == null) {
            return;
        }

        // Since there is a text-change listner on the USD field, we set the USD first, then override the
        // ARRR field manually.
        val zprice = DataModel.mainResponseData?.zecprice ?: 0.0
        amountUSD.setText( (zprice * amt).format(2))

        amountARRR.text =
                "${DataModel.mainResponseData?.tokenName} " + DecimalFormat("#.########").format(amt)
    }

    private fun setAmount(amt: Double?) {
        val zprice = DataModel.mainResponseData?.zecprice

        if (amt == null) {
            txtSendCurrencySymbol.text = "" // Let the placeholder show the "$" sign
        } else {
            txtSendCurrencySymbol.text = "$"
        }

        if (amt == null || zprice == null)
            amountARRR.text = "${DataModel.mainResponseData?.tokenName} 0.0"
        else
            amountARRR.text =
                "${DataModel.mainResponseData?.tokenName} " + DecimalFormat("#.########").format(amt)

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            QrReaderActivity.REQUEST_ADDRESS -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (data?.scheme == "arrr") {
                        sendAddress.setText(data.data?.host ?: "", TextView.BufferType.EDITABLE)

                        var amt = data.data?.getQueryParameter("amt") ?:
                                    data.data?.getQueryParameter("amount")

                        // Remove all commas.
                        amt = amt?.replace(",", ".")

                        if (amt != null) {
                            setAmountArrr(amt.toDoubleOrNull())
                        }

                        val memo = data.data?.getQueryParameter("memo")
                        if (memo != null) {
                            txtSendMemo.setText(memo)
                        }
                    } else {
                        sendAddress.setText(data?.dataString ?: "", TextView.BufferType.EDITABLE)
                    }

                    amountUSD.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
                }
            }
            REQUEST_CONFIRM -> {
                if (resultCode == Activity.RESULT_OK) {
                    // Send async, so that we don't mess up the activity flow
                    Handler().post {
                        val tx = Klaxon().parse<DataModel.TransactionItem>(data?.dataString!!)
                        DataModel.sendTx(tx!!)

                        finish()
                    }
                } else {
                    // Cancel
                }
            }
        }
    }

}
