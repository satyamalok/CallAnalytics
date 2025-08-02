package com.example.callanalytics.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.callanalytics.R
import com.example.callanalytics.adapters.CallLogAdapter
import com.example.callanalytics.database.AppDatabase
import kotlinx.coroutines.launch

class CallLogFragment : Fragment() {

    private lateinit var rvCallLog: RecyclerView
    private lateinit var tvNoData: TextView
    private lateinit var callLogAdapter: CallLogAdapter
    private lateinit var database: AppDatabase

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_call_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        loadCallLog()
    }

    override fun onResume() {
        super.onResume()
        loadCallLog()
    }

    private fun initViews(view: View) {
        rvCallLog = view.findViewById(R.id.rvCallLog)
        tvNoData = view.findViewById(R.id.tvNoData)
        database = AppDatabase.getDatabase(requireContext())
    }

    private fun setupRecyclerView() {
        callLogAdapter = CallLogAdapter()
        rvCallLog.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callLogAdapter
        }
    }

    private fun loadCallLog() {
        lifecycleScope.launch {
            database.callDao().getRecentCalls().collect { calls ->
                if (calls.isNotEmpty()) {
                    callLogAdapter.submitList(calls)
                    rvCallLog.visibility = View.VISIBLE
                    tvNoData.visibility = View.GONE
                } else {
                    rvCallLog.visibility = View.GONE
                    tvNoData.visibility = View.VISIBLE
                }
            }
        }
    }
}