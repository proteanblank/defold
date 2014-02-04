package main

import (
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
)

func main() {
	host := os.Args[1]
	port := 8002
	fmt.Println("test")
	for {
		if resp, err := http.Get(fmt.Sprintf("http://%s:%d/profile", host, port)); err == nil {
			buf, _ := ioutil.ReadAll(resp.Body)
			ioutil.WriteFile("profile", buf, 0777)

			fmt.Println(len(buf))
			break
		}
	}

	if resp, err := http.Get(fmt.Sprintf("http://%s:%d/strings", host, port)); err == nil {
		buf, _ := ioutil.ReadAll(resp.Body)
		ioutil.WriteFile("strings", buf, 0777)
		fmt.Println(resp)
	}
}
