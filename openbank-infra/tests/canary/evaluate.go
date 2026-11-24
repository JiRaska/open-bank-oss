// SPDX-License-Identifier: Apache-2.0
// Evaluate conditions with the installed controller version, not a restatement of its semantics.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"

	"github.com/argoproj/argo-rollouts/pkg/apis/rollouts/v1alpha1"
	"github.com/argoproj/argo-rollouts/utils/evaluate"
	"github.com/sirupsen/logrus"
)

type Case struct {
	Name    string
	Success string
	Failure string
	Values  []string
	Want    string
}

func main() {
	var cases []Case
	if err := json.NewDecoder(os.Stdin).Decode(&cases); err != nil {
		panic(err)
	}
	bad := false
	for _, c := range cases {
		r := []float64{}
		for _, v := range c.Values {
			f, e := strconv.ParseFloat(v, 64)
			if e != nil {
				panic(e)
			}
			r = append(r, f)
		}
		p, e := evaluate.EvaluateResult(r, v1alpha1.Metric{SuccessCondition: c.Success, FailureCondition: c.Failure}, *logrus.NewEntry(logrus.New()))
		fmt.Printf("%s: %s want %s error=%v\n", c.Name, p, c.Want, e)
		if e != nil || string(p) != c.Want {
			bad = true
		}
	}
	if bad {
		os.Exit(1)
	}
}
