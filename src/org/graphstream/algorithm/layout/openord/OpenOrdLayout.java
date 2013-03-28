/*
Copyright 2007 Sandia Corporation. Under the terms of Contract
DE-AC04-94AL85000 with Sandia Corporation, the U.S. Government retains
certain rights in this software.

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
 * Neither the name of Sandia National Laboratories nor the names of
its contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.graphstream.algorithm.layout.openord;

import gnu.trove.iterator.TIntFloatIterator;
import gnu.trove.map.hash.TIntFloatHashMap;
import gnu.trove.map.hash.TIntIntHashMap;
//import gnu.trove.strategy.HashingStrategy;
//import java.util.ArrayList;
//import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;

import org.graphstream.algorithm.Algorithm;
import org.graphstream.graph.Edge;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;

//import org.gephi.graph.api.Edge;
//import org.gephi.graph.api.GraphModel;
//import org.gephi.graph.api.HierarchicalGraph;
//import org.gephi.layout.spi.Layout;
//import org.gephi.layout.spi.LayoutBuilder;
//import org.gephi.layout.spi.LayoutProperty;
//import org.gephi.utils.longtask.spi.LongTask;
//import org.gephi.utils.progress.ProgressTicket;

//import org.openide.util.NbBundle;

/**
 * @author Mathieu Bastian (initial Gephi version)
 * @author Guilhelm Savin (modified version for GraphStream)
 */
public class OpenOrdLayout implements Algorithm {

	// Architecture
	// private LayoutBuilder builder;
	// private GraphModel graphModel;
	private boolean running = true;
	// private ProgressTicket progressTicket;
	// Settings
	private Params param;
	private float edgeCut;
	private int numThreads;
	private long randSeed;
	private int numIterations;
	private float realTime;
	// Layout
	private Worker[] workers;
	private Combine combine;
	private Control control;
	private CyclicBarrier barrier;
	private Graph graph;
	private boolean firstIteration = true;

	public OpenOrdLayout() {
		reset();
	}

	public void reset() {
		edgeCut = 0.8f;
		numIterations = 750;
		numThreads = Math
				.max(1, Runtime.getRuntime().availableProcessors() - 1);
		Random r = new Random();
		randSeed = r.nextLong();
		running = true;
		realTime = 0.2f;
		param = Params.DEFAULT;
	}

	protected float getX(Node n) {
		if (n.hasArray("xyz")) {
			double[] xyz = n.getAttribute("xyz");
			return (float) xyz[0];
		}

		return (float) Math.random();
	}

	protected float getY(Node n) {
		if (n.hasArray("xyz")) {
			double[] xyz = n.getAttribute("xyz");
			return (float) xyz[1];
		}

		return (float) Math.random();
	}

	protected float getWeight(Edge e) {
		double w = e.getNumber("weight");

		if (!Double.isNaN(w))
			return (float) w;

		return 1;
	}

	public void init(Graph g) {
		//
		// Verify param
		//
		if (param.getIterationsSum() != 1f) {
			param = Params.DEFAULT;
			// throw new
			// RuntimeException("The sum of the time for each stage must be equal to 1");
		}

		//
		// Get graph
		//
		graph = g;
		int numNodes = graph.getNodeCount();

		//
		// Prepare data structure - nodes and neighbors map
		//
		OpenOrdNode[] nodes = new OpenOrdNode[numNodes];
		TIntFloatHashMap[] neighbors = new TIntFloatHashMap[numNodes];

		// @SuppressWarnings("serial")
		// HashingStrategy<Integer> hashingStrategy = new
		// HashingStrategy<Integer>() {
		// public int computeHashCode(Integer i) {
		// return i;
		// }
		//
		// public boolean equals(Integer arg0, Integer arg1) {
		// return arg0 == null ? arg1 == null : arg0.equals(arg1);
		// }
		// };

		//
		// Load nodes and edges
		//
		TIntIntHashMap idMap = new TIntIntHashMap(numNodes, 1f);

		for (int i = 0; i < graph.getNodeCount(); i++) {
			org.graphstream.graph.Node n = graph.getNode(i);

			nodes[i] = new OpenOrdNode(i);

			if (n.hasArray("xyz")) {
				nodes[i].x = getX(n);
				nodes[i].y = getY(n);
			}

			nodes[i].fixed = n.hasAttribute("ui.fixed");

			OpenOrdLayoutData layoutData = new OpenOrdLayoutData(i);
			n.setAttribute("layout-data", layoutData);

			idMap.put(n.getIndex(), i);
		}

		float highestSimilarity = Float.NEGATIVE_INFINITY;

		for (int i = 0; i < graph.getEdgeCount(); i++) {
			Edge e = graph.getEdge(i);

			int source = idMap.get(e.getSourceNode().getIndex());
			int target = idMap.get(e.getTargetNode().getIndex());

			if (source != target) { // No self-loop
				float weight = getWeight(e);

				if (neighbors[source] == null)
					neighbors[source] = new TIntFloatHashMap();

				if (neighbors[target] == null)
					neighbors[target] = new TIntFloatHashMap();

				neighbors[source].put(target, weight);
				neighbors[target].put(source, weight);
				highestSimilarity = Math.max(highestSimilarity, weight);
			}
		}
		// graph.readUnlock();

		//
		// Reset position
		//
		boolean someFixed = false;
		for (int i = 0; i < nodes.length; i++) {
			OpenOrdNode n = nodes[i];
			if (!n.fixed) {
				n.x = 0;
				n.y = 0;
			} else {
				someFixed = true;
			}
		}

		//
		// Recenter fixed nodes and rescale to fit into grid
		//
		if (someFixed) {
			float minX = Float.POSITIVE_INFINITY;
			float maxX = Float.NEGATIVE_INFINITY;
			float minY = Float.POSITIVE_INFINITY;
			float maxY = Float.NEGATIVE_INFINITY;

			for (int i = 0; i < nodes.length; i++) {
				OpenOrdNode n = nodes[i];
				if (n.fixed) {
					minX = Math.min(minX, n.x);
					maxX = Math.max(maxX, n.x);
					minY = Math.min(minY, n.y);
					maxY = Math.max(maxY, n.y);
				}
			}

			float shiftX = minX + (maxX - minX) / 2f;
			float shiftY = minY + (maxY - minY) / 2f;
			float ratio = Math.min(DensityGrid.getViewSize() / (maxX - minX),
					DensityGrid.getViewSize() / (maxY - minY));

			ratio = Math.min(1f, ratio);

			for (int i = 0; i < nodes.length; i++) {
				OpenOrdNode n = nodes[i];
				if (n.fixed) {
					n.x = (float) (n.x - shiftX) * ratio;
					n.y = (float) (n.y - shiftY) * ratio;
				}
			}
		}

		//
		// Init control and workers
		//
		control = new Control();
		combine = new Combine(this);
		barrier = new CyclicBarrier(numThreads, combine);
		control.setEdgeCut(edgeCut);
		control.setRealParm(realTime);
		control.initParams(param, numIterations);
		control.setNumNodes(numNodes);
		control.setHighestSimilarity(highestSimilarity);

		workers = new Worker[numThreads];
		for (int i = 0; i < numThreads; ++i) {
			workers[i] = new Worker(i, numThreads, barrier);
			workers[i].setRandom(new Random(randSeed));
			control.initWorker(workers[i]);
		}

		//
		// Load workers with data
		// Deep copy of all nodes positions
		// Deep copy of a partition of all neighbors for each workers
		//
		for (Worker w : workers) {
			OpenOrdNode[] nodesCopy = new OpenOrdNode[nodes.length];

			for (int i = 0; i < nodes.length; i++)
				nodesCopy[i] = nodes[i].clone();

			TIntFloatHashMap[] neighborsCopy = new TIntFloatHashMap[numNodes];

			for (int i = 0; i < neighbors.length; i++) {
				if (i % numThreads == w.getId() && neighbors[i] != null) {
					int neighborsCount = neighbors[i].size();
					neighborsCopy[i] = new TIntFloatHashMap(neighborsCount, 1f);

					for (TIntFloatIterator itr = neighbors[i].iterator(); itr
							.hasNext();) {
						itr.advance();
						float weight = normalizeWeight(itr.value(),
								highestSimilarity);
						neighborsCopy[i].put(itr.key(), weight);
					}
				}
			}

			w.setPositions(nodesCopy);
			w.setNeighbors(neighborsCopy);
		}

		//
		// Add real nodes
		//
		for (int i = 0; i < nodes.length; i++) {
			OpenOrdNode n = nodes[i];
			if (n.fixed) {
				for (int j = 0; j < workers.length; j++) {
					Worker w = workers[j];
					w.getDensityGrid().add(n, w.isFineDensity());
				}
			}
		}

		running = true;
		firstIteration = true;
	}

	public void compute() {
		for (int i = 0; i < getNumIterations(); i++)
			goAlgo();
		
		endAlgo();
	}

	public void goAlgo() {
		if (firstIteration) {
			for (int i = 0; i < numThreads; ++i) {
				Thread t = new Thread(workers[i]);
				t.setDaemon(true);
				t.start();
			}

			firstIteration = false;
		}

		combine.waitForIteration();
	}

	public void endAlgo() {
		running = false;
		combine = null;
	}

	private float normalizeWeight(float weight, float highestSimilarity) {
		weight /= highestSimilarity;
		weight = weight * Math.abs(weight);
		return weight;
	}

	public boolean canAlgo() {
		return running;
	}

	// public LayoutProperty[] getProperties() {
	// List<LayoutProperty> properties = new ArrayList<LayoutProperty>();
	// final String OPENORD = "OpenOrd";
	// final String STAGE = "Stages";
	//
	// try {
	// properties.add(LayoutProperty.createProperty(this, Float.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.edgecut.name"), OPENORD,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.edgecut.description"),
	// "getEdgeCut", "setEdgeCut"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.numthreads.name"), OPENORD,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.numthreads.description"),
	// "getNumThreads", "setNumThreads"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.numiterations.name"), OPENORD,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.numiterations.description"),
	// "getNumIterations", "setNumIterations"));
	// properties.add(LayoutProperty.createProperty(this, Float.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.realtime.name"), OPENORD,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.realtime.description"),
	// "getRealTime", "setRealTime"));
	// properties.add(LayoutProperty.createProperty(this, Long.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.seed.name"), OPENORD, NbBundle
	// .getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.seed.description"),
	// "getRandSeed", "setRandSeed"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.liquid.name"), STAGE,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.liquid.description"),
	// "getLiquidStage", "setLiquidStage"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.expansion.name"), STAGE,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.expansion.description"),
	// "getExpansionStage", "setExpansionStage"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.cooldown.name"), STAGE,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.cooldown.description"),
	// "getCooldownStage", "setCooldownStage"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.crunch.name"), STAGE,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.crunch.description"),
	// "getCrunchStage", "setCrunchStage"));
	// properties.add(LayoutProperty.createProperty(this, Integer.class,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.simmer.name"), STAGE,
	// NbBundle.getMessage(OpenOrdLayout.class,
	// "OpenOrd.properties.stage.simmer.description"),
	// "getSimmerStage", "setSimmerStage"));
	//
	// } catch (Exception e) {
	// e.printStackTrace();
	// }
	//
	// return properties.toArray(new LayoutProperty[0]);
	// }

	public Float getEdgeCut() {
		return edgeCut;
	}

	public void setEdgeCut(Float edgeCut) {
		edgeCut = Math.min(1f, edgeCut);
		edgeCut = Math.max(0, edgeCut);
		this.edgeCut = edgeCut;
	}

	public Integer getNumThreads() {
		return numThreads;
	}

	public void setNumThreads(Integer numThreads) {
		numThreads = Math.max(1, numThreads);
		this.numThreads = numThreads;
	}

	public Long getRandSeed() {
		return randSeed;
	}

	public void setRandSeed(Long randSeed) {
		this.randSeed = randSeed;
	}

	public void setRunning(Boolean running) {
		this.running = running;
	}

	public Integer getNumIterations() {
		return numIterations;
	}

	public void setNumIterations(Integer numIterations) {
		numIterations = Math.max(100, numIterations);
		this.numIterations = numIterations;
	}

	public Float getRealTime() {
		return realTime;
	}

	public void setRealTime(Float realTime) {
		realTime = Math.min(1f, realTime);
		realTime = Math.max(0, realTime);
		this.realTime = realTime;
	}

	public Integer getLiquidStage() {
		return param.getLiquid().getIterationsPercentage();
	}

	public Integer getExpansionStage() {
		return param.getExpansion().getIterationsPercentage();
	}

	public Integer getCooldownStage() {
		return param.getCooldown().getIterationsPercentage();
	}

	public Integer getCrunchStage() {
		return param.getCrunch().getIterationsPercentage();
	}

	public Integer getSimmerStage() {
		return param.getSimmer().getIterationsPercentage();
	}

	public void setLiquidStage(Integer value) {
		int v = Math.min(100, value);
		v = Math.max(0, v);
		param.getLiquid().setIterations(v / 100f);
	}

	public void setExpansionStage(Integer value) {
		int v = Math.min(100, value);
		v = Math.max(0, v);
		param.getExpansion().setIterations(v / 100f);
	}

	public void setCooldownStage(Integer value) {
		int v = Math.min(100, value);
		v = Math.max(0, v);
		param.getCooldown().setIterations(v / 100f);
	}

	public void setCrunchStage(Integer value) {
		int v = Math.min(100, value);
		v = Math.max(0, v);
		param.getCrunch().setIterations(v / 100f);
	}

	public void setSimmerStage(Integer value) {
		int v = Math.min(100, value);
		v = Math.max(0, v);
		param.getSimmer().setIterations(v / 100f);
	}

	public Worker[] getWorkers() {
		return workers;
	}

	public Graph getGraph() {
		return graph;
	}

	public Control getControl() {
		return control;
	}
}
